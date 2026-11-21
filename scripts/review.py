#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""The review of a push: questions about the code you wrote, answered before it goes out.

Usage: review.py status [REV]            say whether REV (default HEAD) needs a review
       review.py ask                     answer the questions about HEAD (make review)
       review.py push-check REV          the pre-push hook: refuse REV without its review
       review.py verify [REV] [--actor LOGIN]
                                         CI: every graded change up to REV is reviewed
       review.py show [REV]              print the review recorded on REV

Your commits are those that the upstream repository (sysprog21/ca2026-mycpu, main) does not have.
When they change a graded file (the Scala sources of a task, or the routines.S of a quiz
program), a push of them needs a review: `make review` picks two lines that you added there
where a blank was or where you changed given code (the first 12 lines of a hunk that also
removes a line; any added line when there are not two of these), chosen by your ID and the
change itself, quotes each with the exercise around it, and asks a question about it, such as
what the line decides and in which cycle, or what goes wrong if it changes. Each answer needs
your own words:

  - at least 20 words (a CJK character counts as a word), most of them not copied from the code
    or the question, and not the same few words repeated;
  - at least one name (a signal, a register, a label) from the code around the quoted line.

`git push` triggers the review: when it is missing, the pre-push hook asks the questions on the
terminal (or refuses the push, without one; `make review` then asks them). The questions, the
answers and the hash of the reviewed change are recorded as a git note on HEAD
(refs/notes/mycpu-review), which the pre-push hook pushes with the branch. The CI of your
fork checks with the upstream copy of this script that every graded change up to the pushed
commit has a review. The instructors read the answers before the live defense, where you
explain them again. A rebase or an amend makes new commits without the note: review again.

The owners of the repository (.github/CODEOWNERS of the upstream commit, never the fork's) are exempt: in CI the account that pushes must
be one of them; on your machine `git config mycpu.codeOwner <login>` names yours, which CI never
reads.
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import time

def _root():
    """The repository of the current directory (CI runs the upstream copy of this script on the
    fork's checkout), or this script's own."""
    p = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    return p.stdout.strip() if p.returncode == 0 else os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


ROOT = _root()
NOTES = "refs/notes/mycpu-review"
UPSTREAM_URL = "https://github.com/sysprog21/ca2026-mycpu.git"
UPSTREAM_REFS = ("refs/remotes/upstream/main", "refs/mycpu-review/upstream")
GRADED = (re.compile(r"^[1-5]-[^/]+/src/main/scala/.+\.scala$"),
          re.compile(r"^software/programs/quiz-[^/]+/routines\.S$"))
QUESTIONS = 2
MIN_WORDS = 20
# 1: the questions were chosen by the commit; 2: by the change itself (an amend that changes only
# the commit's date or message asks the same questions), among the lines that replace a line of the
# code before them (a blank filled in, a line fixed) when the change has any
SCHEMA = 2
SCHEMAS = (1, 2)

SCALA_PROMPTS = [
    "What does this line decide, and in which clock cycle does its value matter? Name a signal "
    "that reads it.",
    "Give one input for which this line matters and the value it produces then. Which test or "
    "program of the task fails if the line is wrong, and how?",
    "Why is it written this way and not in a similar way (another operator, width, condition or "
    "order)? What would go wrong with the other one?",
]
ASM_PROMPTS = [
    "What is this instruction for in your routine, and what would one call cost (more or fewer "
    "cycles, and why) without it?",
    "Which registers does this instruction read and write here, and which rule of the calling "
    "convention must the routine keep around it?",
]
# words too common in the code to show that an answer is about it
COMMON = {"val", "var", "def", "when", "otherwise", "elsewhen", "true", "false", "uint", "sint", "bool",
          "the", "and", "for", "not", "with", "this", "that", "from", "into", "io", "bits", "addi", "li"}


class ReviewError(Exception):
    pass


def git(*args, check=True, inp=None):
    p = subprocess.run(["git", "-C", ROOT, *args], input=inp, capture_output=True, text=True)
    if check and p.returncode:
        raise ReviewError(f"git {' '.join(args)}: {p.stderr.strip()}")
    return p.stdout.strip() if p.returncode == 0 else ""


def rev(name):
    out = git("rev-parse", "--verify", "-q", f"{name}^{{commit}}", check=False)
    if not out:
        raise ReviewError(f"no commit {name}")
    return out


def upstream(fetch):
    """The upstream main of the course, fetched first when `fetch` (pre-push, CI)."""
    if fetch and not os.environ.get("MYCPU_REVIEW_OFFLINE"):  # the tests set it
        git("fetch", "--quiet", "--no-tags", UPSTREAM_URL, f"+main:{UPSTREAM_REFS[1]}", check=False)
    for ref in UPSTREAM_REFS:
        out = git("rev-parse", "--verify", "-q", ref, check=False)
        if out:
            return out
    raise ReviewError(f"the upstream repository is not fetched: git fetch {UPSTREAM_URL} main:{UPSTREAM_REFS[1]}")


def owners(up):
    """The logins of .github/CODEOWNERS as the upstream commit has it: a fork cannot add its own."""
    found = set()
    for line in git("show", f"{up}:.github/CODEOWNERS", check=False).splitlines():
        line = line.split("#", 1)[0]
        found.update(w[1:].lower() for w in line.split()[1:] if w.startswith("@") and "/" not in w)
    return found


def graded(path):
    return any(r.match(path) for r in GRADED)


def note(commit):
    text = git("notes", f"--ref={NOTES}", "show", commit, check=False)
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        return None


def pending(head, up):
    """(own commits of head not covered by a review, oldest first; the reviewed ancestors)."""
    own = git("rev-list", head, f"^{up}").split()
    reviewed = [c for c in own if note(c) is not None]
    rest = git("rev-list", "--reverse", head, f"^{up}", *[f"^{c}" for c in reviewed]).split() if own else []
    return rest, reviewed


def graded_files(commits):
    files = set()
    for c in commits:
        files.update(f for f in git("diff-tree", "--no-commit-id", "-r", "--name-only", "--root", c).split() if graded(f))
    return sorted(files)


def change_hash(base, head, files):
    return hashlib.sha256(git("diff", "--no-color", "--no-ext-diff", base, head, "--", *files).encode()).hexdigest()


# the longest answer of a blank is a body of 11 statements: lines of a hunk after this many are
# not where a blank was
HUNK_LINES = 12


def added_lines(base, head, files, replacing=False):
    """(file, new line number, text) of the lines that base..head adds to the files; with
    replacing, only the first HUNK_LINES of a hunk that also removes a line (a blank filled in, a
    line changed), not those that the change only inserts or appends after them."""
    out, path, line, removes, n = [], None, 0, False, 0
    for text in git("diff", "--no-color", "--no-ext-diff", "-U0", base, head, "--", *files).split("\n"):
        if text.startswith("+++ "):
            path = text[6:] if text.startswith("+++ b/") else None
        elif text.startswith("@@"):
            m = re.match(r"@@ -\d+(?:,(\d+))? \+(\d+)", text)
            line = int(m.group(2)) if m else 0
            removes, n = bool(m) and m.group(1) != "0", 0
        elif text.startswith("+") and path:
            if not replacing or (removes and n < HUNK_LINES):
                out.append((path, line, text[1:]))
            line += 1
            n += 1
    return out


def worth_asking(path, text):
    t = text.strip()
    if len(re.sub(r"\s", "", t)) < 12 or t.startswith(("//", "#", "/*", "*")):
        return False
    return bool(re.search(r"[A-Za-z_][A-Za-z0-9_]*", t)) and not re.fullmatch(r"[{}()\[\];,]*", t)


def context(head, path, line, around=6):
    lines = git("show", f"{head}:{path}").split("\n")
    lo, hi = max(0, line - 1 - around), min(len(lines), line + around)
    exercise = None
    for t in lines[: line]:
        m = re.search(r"\[CA26: Exercise (\d+\.\d+)\]", t)
        if m:
            exercise = m.group(1)
    return lines[lo:hi], exercise


def choose(student, seed, candidates):
    """QUESTIONS lines, different files first: a function of the ID, the seed (the change, or the
    commit in schema 1) and the candidates alone, so that CI chooses the same ones again and a note
    cannot pick its own."""
    def key(c):
        return hashlib.sha256(f"{student}\n{seed}\n{c[0]}:{c[1]}:{c[2].strip()}".encode()).hexdigest()
    chosen, files = [], set()
    for c in sorted(candidates, key=key):
        if c[0] not in files or len({x[0] for x in candidates}) <= len(files):
            chosen.append(c)
            files.add(c[0])
        if len(chosen) == QUESTIONS:
            break
    return chosen


def prompt_for(seed, path, line):
    prompts = ASM_PROMPTS if path.endswith(".S") else SCALA_PROMPTS
    return prompts[int(hashlib.sha256(f"{seed}\n{path}\n{line}".encode()).hexdigest(), 16) % len(prompts)]


def questions_for(student, head, base, files, schema=SCHEMA):
    """[(file, line, quote, prompt)] that the review of head asks, by the rules of schema."""
    if schema == 1:
        seed, candidates = head, [c for c in added_lines(base, head, files) if worth_asking(c[0], c[2])]
    else:
        seed = change_hash(base, head, files)
        candidates = [c for c in added_lines(base, head, files, replacing=True) if worth_asking(c[0], c[2])]
        if len(candidates) < QUESTIONS:
            candidates = [c for c in added_lines(base, head, files) if worth_asking(c[0], c[2])]
    return [(f, line, text, prompt_for(seed, f, line)) for f, line, text in choose(student, seed, candidates)]


def words(text):
    return re.findall(r"[A-Za-z0-9_]+|[㐀-鿿]", text)


def check_answer(answer, quote, prompt, near):
    """None if the answer is acceptable, otherwise why not."""
    toks = words(answer)
    lower = [t.lower() for t in toks]
    own = [t for t in lower if t not in set(x.lower() for x in words(quote + " " + prompt))]
    if len(toks) < MIN_WORDS:
        return f"{len(toks)} words: write at least {MIN_WORDS} (a CJK character counts as one)"
    if len(own) < MIN_WORDS // 2:
        return "most of the answer repeats the code or the question: say it in your own words"
    if len(set(lower)) < len(lower) * 0.4:
        return "the answer repeats the same few words"
    names = {n.lower() for n in re.findall(r"[A-Za-z_][A-Za-z0-9_]{2,}", "\n".join(near))} - COMMON
    if not names & set(lower):
        return "name at least one signal, register or label of the code around the line"
    return None


def student_id(commit="HEAD"):
    """The ID that the commit records (its STUDENT_ID), which the selection of the lines depends on."""
    return git("show", f"{commit}:STUDENT_ID", check=False).strip() or "unset"


def is_owner_here(up):
    """`git config mycpu.codeOwner` names an owner. Upstream without CODEOWNERS (the push that adds
    it) falls back to HEAD's: only here, which CI never reads."""
    login = git("config", "mycpu.codeOwner", check=False).lower()
    listed = owners(up) or owners("HEAD")
    return bool(login) and login in listed


def status(head, fetch):
    up = upstream(fetch)
    rest, reviewed = pending(head, up)
    files = graded_files(rest)
    return up, rest, reviewed, files


def cmd_status(args):
    head = rev(args[0] if args else "HEAD")
    _, rest, reviewed, files = status(head, fetch=False)
    if not files:
        print(f"review: no review needed: none of the {len(rest)} commit(s) since the last review changes a graded file")
        return 0
    print(f"review: needed for {len(rest)} commit(s) that change {', '.join(files)} (make review)")
    return 1


def ask_one(i, q, near):
    print(f"\nQuestion {i} of {QUESTIONS}: {q['file']}:{q['line']}" + (f" (Exercise {q['exercise']})" if q["exercise"] else ""))
    print("    " + "\n    ".join(near))
    print(f"\n  The line: {q['quote'].strip()}\n  {q['prompt']}")
    while True:
        print("  Your answer (end with a line that holds only a dot):")
        lines = []
        for line in sys.stdin:
            if line.strip() == ".":
                break
            lines.append(line.rstrip("\n"))
        else:
            raise ReviewError("the input ended before the answer")
        answer = "\n".join(lines).strip()
        why = check_answer(answer, q["quote"], q["prompt"], near)
        if why is None:
            return answer
        print(f"  Not yet: {why}.")


def cmd_ask(args):
    head = rev("HEAD")
    up = upstream(fetch=True)
    base, files = scope(head, up)
    if not files:
        print("review: no review needed: your commits since the last review change no graded file")
        return 0
    student = student_id(head)
    chosen = questions_for(student, head, base, files)
    if not chosen:
        raise ReviewError("the graded change adds no line to ask about; commit your work first")
    questions = []
    for n, (f, line, text, prompt) in enumerate(chosen, 1):
        near, exercise = context(head, f, line)
        q = {"file": f, "line": line, "exercise": exercise, "quote": text, "prompt": prompt}
        q["answer"] = ask_one(n, q, near)
        questions.append(q)
    record = {"schema": SCHEMA, "student": student, "email": git("config", "user.email", check=False),
              "head": head, "base": base, "upstream": up, "files": files,
              "change_sha256": change_hash(base, head, files), "questions": questions,
              "answered_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    git("notes", f"--ref={NOTES}", "add", "-f", "-F", "-", head, inp=json.dumps(record, indent=1, ensure_ascii=False) + "\n")
    print(f"\nreview: recorded on {head[:12]} ({NOTES}); the pre-push hook pushes it with the branch")
    return 0


def scope(commit, up):
    """(base, graded files) of the review of commit: its own commits since the reviewed ancestors
    before it, as `make review` saw them."""
    own = git("rev-list", commit, f"^{up}").split()
    before = [c for c in own if c != commit and note(c) is not None]
    rest = git("rev-list", "--reverse", commit, f"^{up}", *[f"^{c}" for c in before]).split()
    if not rest:
        return up, []
    has_parent = git("rev-list", "--parents", "-n", "1", rest[0]).count(" ") > 0
    return (rev(f"{rest[0]}^") if has_parent else up), graded_files(rest)


def valid(record, commit, up):
    """None if a recorded review stands for commit, otherwise why not."""
    if not isinstance(record, dict) or record.get("schema") not in SCHEMAS or record.get("head") != commit:
        return "the note is not a review of this commit"
    qs = record.get("questions") or []
    if len(qs) < QUESTIONS:
        return f"{len(qs)} answered question(s), not {QUESTIONS}"
    base, files = scope(commit, up)
    if record.get("base") != base or record.get("files") != files:
        return "the note reviews another range of commits than this one's"
    try:
        if change_hash(record["base"], commit, record["files"]) != record["change_sha256"]:
            return "the reviewed change is not this commit's"
    except ReviewError as e:
        return str(e)
    try:
        want = questions_for(student_id(commit), commit, record["base"], record["files"], record["schema"])
    except (ReviewError, KeyError, TypeError) as e:
        return f"the questions cannot be chosen again: {e}"
    got = [(q.get("file"), q.get("line"), q.get("quote"), q.get("prompt")) for q in qs]
    if got != want:
        return "the questions are not the ones the review chooses for this commit (a note written by hand?)"
    for q in qs:
        try:
            near, _ = context(commit, q["file"], q["line"])
        except (ReviewError, KeyError):
            return "a question names a line that the commit does not have"
        why = check_answer(q.get("answer", ""), q.get("quote", ""), q.get("prompt", ""), near)
        if why:
            return f"the answer about {q['file']}:{q['line']}: {why}"
    return None


def check(head, fetch):
    """None when every graded change up to head is reviewed, otherwise why not."""
    up, rest, reviewed, files = status(head, fetch)
    for c in reviewed:
        why = valid(note(c), c, up)
        if why:
            return f"the review on {c[:12]}: {why}"
    if files:
        return (f"{len(rest)} commit(s) up to {head[:12]} change {', '.join(files)} without a review: "
                "run `make review`, then push again")
    return None


def cmd_push_check(args):
    if is_owner_here(upstream(fetch=True)):
        return 0
    why = check(rev(args[0]), fetch=True)
    if why:
        print(f"review: {why}", file=sys.stderr)
        return 1
    return 0


def cmd_verify(args):
    actor = None
    if "--actor" in args:
        i = args.index("--actor")
        actor = args[i + 1].lower() if i + 1 < len(args) else ""
        args = args[:i] + args[i + 2:]
    head = rev(args[0] if args else "HEAD")
    if actor and actor in owners(upstream(fetch=False)):
        print(f"review: {actor} owns the repository (.github/CODEOWNERS): no review needed")
        return 0
    why = check(head, fetch=False)
    if why:
        print(f"::error title=Review missing::{why}")
        return 1
    print(f"review: every graded change up to {head[:12]} is reviewed")
    return 0


def cmd_show(args):
    head = rev(args[0] if args else "HEAD")
    record = note(head)
    if record is None:
        print(f"review: no review on {head[:12]}")
        return 1
    print(json.dumps(record, indent=1, ensure_ascii=False))
    return 0


def main(argv):
    cmds = {"status": cmd_status, "ask": cmd_ask, "push-check": cmd_push_check, "verify": cmd_verify, "show": cmd_show}
    if not argv or argv[0] not in cmds:
        print(__doc__.split("\n\n")[0] + "\n\n" + __doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    try:
        return cmds[argv[0]](argv[1:])
    except ReviewError as e:
        print(f"review: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
