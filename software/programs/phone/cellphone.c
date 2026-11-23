// SPDX-License-Identifier: MIT
/* A resource-bounded phone interface: no malloc, floating point, filesystem,
 * image decoder or retained widget tree.
 *
 * Rendering is immediate mode with damage tracking. Every state change
 * invalidates the rectangles it affects; cp_render() then replays the screen
 * description once per damaged rectangle with each primitive clipped to it.
 * A cheap probe replay first finds the last opaque fill covering the whole
 * rectangle so hidden layers under it are skipped. Screen changes reveal the
 * new screen in strips on successive ticks; no second framebuffer exists. */
#include "cellphone.h"
#include "assets.h"
#include "font.h"

/* Host builds (tests, the Verilator oracle) check internal limits loudly. */
#ifdef __riscv
#define CP_ASSERT(x) ((void) 0)
#else
#include <assert.h>
#define CP_ASSERT(x) assert(x)
#endif

#define RGB(r, g, b) \
    ((uint16_t) ((((r) >> 3) << 11) | (((g) >> 2) << 5) | ((b) >> 3)))
#define WHITE 0xffff
#define BLACK RGB(10, 12, 16)
#define INK RGB(24, 39, 52)
#define ACCENT RGB(36, 190, 156)
#define RED RGB(235, 70, 88)
#define BLUE RGB(59, 130, 246)
#define KEYC RGB(65, 88, 111)
#define TOP 44     /* list/content top below a title */
#define BOTTOM 298 /* content bottom above the navbar */
#define NAVY 300
#define STRIPS 10 /* wipe steps; each must fit one tick's render budget */
#define MAX_BUTTONS 48
#define MAX_DAMAGE 10
#define SCALE 1000
#define LIMIT 9999999999LL
#define IDLE_LOCK_MS 30000u

cp_state cp;

typedef struct {
    uint8_t x, w, h, pad;
    uint16_t y, action;
} button;
static button buttons[MAX_BUTTONS];
static uint8_t button_count, layout, probing, hit_seq;
static int16_t damage[MAX_DAMAGE][4], clip[4], area[4];
static uint8_t ndamage;
static int16_t hit_y0, hit_y1 = CP_H;
static uint16_t prim, first;
static uint16_t fg, bg, panel, muted, bar;

/* Button actions: kind in the high byte. Key codes never reach action(). */
enum { K_NAV = 1, K_APP, K_ITEM, K_CHAR, K_CMD, K_SET };
#define ACT(k, a) ((unsigned) (k) << 8 | (unsigned) (a))
enum {
    C_CALL = 1,
    C_DEL,
    C_SEND,
    C_SHIFT,
    C_LAYER,
    C_KBD_CLOSE,
    C_KBD_OPEN,
    C_PREV,
    C_PLAY,
    C_NEXT,
    C_LIST,
    C_CAPTURE,
    C_END,
    C_MESSAGE,
    C_PAGE0,
    C_PAGE1,
    C_ADD,
    C_SAVE,
    C_CANCEL,
    C_MEMORY,
    C_FIELD0 /* + field */
};
enum {
    S_BRIGHT_DN,
    S_BRIGHT_UP,
    S_VOL_DN,
    S_VOL_UP,
    S_THEME0,
    S_THEME1,
    S_WALL0,
    S_WALL1,
    S_WALL2,
    S_HOUR_UP,
    S_MIN_UP,
    S_HOUR_DN,
    S_MIN_DN
};
enum { D_PUSH, D_POP, D_UNLOCK, D_LOCK, D_PHOTOS };
enum { G_NONE, G_LIST, G_SEEK, G_KNOB, G_LEVEL };

static const char *const names[] = {
    "Phone",  "Contacts", "Messages", "Calculator", "Music", "Photos",
    "Camera", "Settings", "Call Log", "Snake",      "Pong",  "Tetris"};
static const uint16_t colors[] = {
    RGB(34, 197, 94),   RGB(59, 130, 246),  RGB(16, 185, 129),
    RGB(245, 158, 11),  RGB(236, 72, 153),  RGB(249, 115, 22),
    RGB(107, 114, 128), RGB(100, 116, 139), RGB(139, 92, 246),
    RGB(20, 184, 166),  RGB(99, 102, 241),  RGB(239, 68, 68)};
static const uint16_t tetris_colors[7] = {
    RGB(34, 211, 238), RGB(250, 204, 21), RGB(168, 85, 247), RGB(34, 197, 94),
    RGB(239, 68, 68),  RGB(59, 130, 246), RGB(249, 115, 22)};
static const char *const wday_names[] = {"SUNDAY",    "MONDAY",   "TUESDAY",
                                         "WEDNESDAY", "THURSDAY", "FRIDAY",
                                         "SATURDAY"};
static const char *const month_names[] = {
    "JANUARY", "FEBRUARY", "MARCH",     "APRIL",   "MAY",      "JUNE",
    "JULY",    "AUGUST",   "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"};
static const uint8_t month_days[] = {31, 28, 31, 30, 31, 30,
                                     31, 31, 30, 31, 30, 31};
static const char *const settings_names[] = {"Display", "Sound", "Wallpaper",
                                             "Time",    "About", "Lock screen"};
static const char *const wallpaper_names[] = {"SAGE", "SAND", "SLATE"};
/* Wallpaper gradients: base RGB and slope per 64 rows, light then dark. */
static const int8_t wall_slope[2][3][3] = {
    {{-11, -6, -5}, {-10, -12, -12}, {-9, -9, -6}},
    {{5, 7, 13}, {7, 5, 3}, {6, 7, 9}}};
static const uint8_t wall_base[2][3][3] = {
    {{194, 224, 215}, {236, 214, 178}, {176, 196, 222}},
    {{12, 24, 48}, {40, 26, 20}, {16, 22, 36}}};
static const char *const kbd_rows[3][3] = {
    {"qwertyuiop", "asdfghjkl", "zxcvbnm"},
    {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"},
    {"1234567890", "@#$_()-+=", "'!?,.:/"}};
static const char *const dial_letters[] = {
    "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};

/* ---------------------------------------------------------------- strings */
static unsigned length(const char *s)
{
    unsigned n = 0;
    while (s[n])
        n++;
    return n;
}
static void copy(char *d, const char *s, unsigned capacity)
{
    unsigned i = 0;
    while (s[i] && i + 1 < capacity) {
        d[i] = s[i];
        i++;
    }
    d[i] = 0;
}
static char *number(char *s, int32_t value)
{
    char tmp[12];
    unsigned n = 0;
    uint32_t v = value < 0 ? 0u - (uint32_t) value : (uint32_t) value;
    if (value < 0)
        *s++ = '-';
    do {
        tmp[n++] = (char) ('0' + v % 10);
        v /= 10;
    } while (v);
    while (n)
        *s++ = tmp[--n];
    *s = 0;
    return s;
}
static char *two(char *s, unsigned v)
{
    s[0] = (char) ('0' + v / 10 % 10);
    s[1] = (char) ('0' + v % 10);
    s[2] = 0;
    return s + 2;
}
static void percent(char *s, unsigned v)
{
    s = number(s, (int32_t) v);
    s[0] = '%';
    s[1] = 0;
}
static void hhmm(char *s, unsigned minute)
{
    s = two(s, minute / 60);
    *s++ = ':';
    two(s, minute % 60);
}
static void mmss(char *s, unsigned secs)
{
    s = number(s, (int32_t) (secs / 60));
    *s++ = ':';
    two(s, secs % 60);
}
static void append(char *d, const char *s, unsigned capacity)
{
    unsigned n = length(d);
    if (n < capacity)
        copy(d + n, s, capacity - n);
}
/* "WEDNESDAY, SEPTEMBER 30" is 24 bytes; output is cut at `capacity`. */
static void date(char *s, unsigned capacity)
{
    char d[12];
    copy(s, wday_names[cp.wday % 7], capacity);
    append(s, ", ", capacity);
    append(s, month_names[cp.month % 12], capacity);
    append(s, " ", capacity);
    number(d, cp.mday);
    append(s, d, capacity);
}

/* Contact ids: 0..11 are the stock contacts, 12.. the ones added here. */
#define N_ALL (N_CONTACTS + CP_USERS)
static unsigned contacts_n(void)
{
    return N_CONTACTS + cp.users;
}
static const char *cname(unsigned id)
{
    return id < N_CONTACTS ? contact_names[id]
                           : cp.user[(id - N_CONTACTS) % CP_USERS].name;
}
static const char *cphone(unsigned id)
{
    return id < N_CONTACTS ? contact_phones[id]
                           : cp.user[(id - N_CONTACTS) % CP_USERS].phone;
}
static const char *cemail(unsigned id)
{
    return id < N_CONTACTS ? contact_emails[id]
                           : cp.user[(id - N_CONTACTS) % CP_USERS].email;
}
static unsigned upper(unsigned c)
{
    return c >= 'a' && c <= 'z' ? c - 32 : c;
}

/* ------------------------------------------------------- clipped drawing */
static uint16_t dim(uint16_t c)
{
    unsigned b = cp.brightness;
    if (b >= 10)
        return c;
    // GCC turns the constant divisions into multiplies (mulhu); spelling
    // them as x * 205 >> 11 measured 9% slower full updates (inlining).
    return (uint16_t) (((c >> 11) * b / 10) << 11 |
                       (((c >> 5) & 63) * b / 10) << 5 | (c & 31) * b / 10);
}
static uint16_t shade(uint16_t c)
{
    return (uint16_t) ((c >> 1) & 0x7bef);
}
static int cut(int *x, int *y, int *w, int *h)
{
    int x0 = *x, y0 = *y, x1 = x0 + *w, y1 = y0 + *h;
    if (x0 < clip[0])
        x0 = clip[0];
    if (y0 < clip[1])
        y0 = clip[1];
    if (x1 > clip[2])
        x1 = clip[2];
    if (y1 > clip[3])
        y1 = clip[3];
    if (x0 >= x1 || y0 >= y1)
        return 0;
    *x = x0;
    *y = y0;
    *w = x1 - x0;
    *h = y1 - y0;
    return 1;
}
static int vis(int x, int y, int w, int h)
{
    return x < clip[2] && y < clip[3] && x + w > clip[0] && y + h > clip[1];
}
static int inside(int x, int y, int w, int h)
{
    return x >= clip[0] && y >= clip[1] && x + w <= clip[2] && y + h <= clip[3];
}
static void raw(int x, int y, int w, int h, uint16_t c)
{
    if (cut(&x, &y, &w, &h))
        cp_fill(x, y, w, h, c);
}
/* Is this clipped primitive opaque over the whole damage rectangle? */
static int covers(int x, int y, int w, int h)
{
    return cut(&x, &y, &w, &h) && x == area[0] && y == area[1] &&
           x + w == area[2] && y + h == area[3];
}
static void fill(int x, int y, int w, int h, uint16_t c)
{
    unsigned n = prim++;
    if (probing) {
        if (covers(x, y, w, h))
            first = (uint16_t) n;
    } else if (n >= first)
        raw(x, y, w, h, dim(c));
}
static void glyph(int x, int y, unsigned ch, uint16_t c, int s)
{
    unsigned n = prim++;
    if (probing || n < first || !vis(x, y, 5 * s, 8 * s))
        return;
    if (ch < 32 || ch > 126)
        ch = '?';
    // font_index() reduced by hand: printable ASCII lies below the gap.
    const uint8_t *g = font_5x8.glyphs + (ch - 32) * 8;
    c = dim(c);
    if (inside(x, y, 5 * s, 8 * s)) {
        cp_glyph(x, y, g, c, (unsigned) s);
        return;
    }
    // Clipped glyph: a fill command per enlarged dot, but plain pixel stores
    // at scale 1, where a command would cost more than the dot itself.
    for (int r = 0; r < 8; r++)
        for (int b = 0; b < 5; b++)
            if (g[r] & (0x80u >> b)) {
                int px = x + b * s, py = y + r * s;
                if (s > 1)
                    raw(px, py, s, s, c);
                else if (px >= clip[0] && px < clip[2] && py >= clip[1] &&
                         py < clip[3])
                    cp_pixel(px, py, c);
            }
}
static void textn(int x,
                  int y,
                  const char *s,
                  unsigned n,
                  uint16_t c,
                  int scale)
{
    if (y >= clip[3] || y + 8 * scale <= clip[1])
        return;
    // Skip the characters left of the clip and stop at its right edge.
    for (; *s && n && x + 5 * scale <= clip[0]; s++, n--)
        x += 6 * scale;
    for (; *s && n && x + 5 * scale <= CP_W && x < clip[2];
         s++, n--, x += 6 * scale)
        glyph(x, y, (unsigned char) *s, c, scale);
}
static void text(int x, int y, const char *s, uint16_t c, int scale)
{
    textn(x, y, s, 255, c, scale);
}
static void center(int x, int y, int w, const char *s, uint16_t c, int scale)
{
    text(x + (w - (int) length(s) * 6 * scale) / 2, y, s, c, scale);
}
static void right(int x, int y, const char *s, uint16_t c, int scale)
{
    text(x - (int) length(s) * 6 * scale, y, s, c, scale);
}
static void num(int x, int y, int32_t v, uint16_t c, int scale)
{
    char s[12];
    number(s, v);
    text(x, y, s, c, scale);
}
static void bitmap(int x, int y, const uint8_t *px, int s)
{
    unsigned n = prim++;
    if (probing) {
        if (covers(x, y, 48 * s, 32 * s))
            first = (uint16_t) n;
        return;
    }
    if (n < first || !vis(x, y, 48 * s, 32 * s))
        return;
    if (inside(x, y, 48 * s, 32 * s)) {
        cp_bitmap8(x, y, px, photo_palette, (unsigned) s);
        return;
    }
    for (int r = 0; r < 32; r++)
        if (vis(x, y + r * s, 48 * s, s))
            for (int col = 0; col < 48; col++)
                raw(x + col * s, y + r * s, s, s,
                    photo_palette[px[r * 48 + col]]);
}
static void line(int x, int y, int x1, int y1, uint16_t c)
{
    unsigned n = prim++;
    int dx = x1 > x ? x1 - x : x - x1, sx = x < x1 ? 1 : -1,
        dy = y1 > y ? y - y1 : y1 - y, sy = y < y1 ? 1 : -1, err = dx + dy;
    if (probing || n < first ||
        !vis(x < x1 ? x : x1, y < y1 ? y : y1, dx + 1, 1 - dy))
        return;
    c = dim(c);
    for (;;) {
        if (x >= clip[0] && x < clip[2] && y >= clip[1] && y < clip[3])
            cp_pixel(x, y, c);
        if (x == x1 && y == y1)
            break;
        int e = err * 2;
        if (e >= dy) {
            err += dy;
            x += sx;
        }
        if (e <= dx) {
            err += dx;
            y += sy;
        }
    }
}
static void roundrect(int x, int y, int w, int h, uint16_t c)
{
    if (!vis(x, y, w, h))
        return;
    if (w < 10 || h < 10) {
        fill(x, y, w, h, c);
        return;
    }
    fill(x + 4, y, w - 8, 1, c);
    fill(x + 2, y + 1, w - 4, 2, c);
    fill(x + 1, y + 3, w - 2, 2, c);
    fill(x, y + 5, w, h - 10, c);
    fill(x + 1, y + h - 5, w - 2, 2, c);
    fill(x + 2, y + h - 3, w - 4, 2, c);
    fill(x + 4, y + h - 1, w - 8, 1, c);
}
static void circle(int x, int y, int r, uint16_t c)
{
    // Monotonic integer spans: the edge retreats at most r times for the
    // entire disc, instead of restarting a square search on every scanline.
    if (!vis(x - r, y - r, 2 * r + 1, 2 * r + 1))
        return;
    int i = r, edge = r * r, limit = edge, row = 0;
    for (int j = 0; j <= r; j++) {
        while (edge + row > limit) {
            edge -= 2 * i - 1;
            i--;
        }
        if (y + j >= clip[1] && y + j < clip[3])
            fill(x - i, y + j, 2 * i + 1, 1, c);
        if (j && y - j >= clip[1] && y - j < clip[3])
            fill(x - i, y - j, 2 * i + 1, 1, c);
        row += 2 * j + 1;
    }
}
static void push_clip(int x, int y, int w, int h, int16_t *save)
{
    for (int i = 0; i < 4; i++)
        save[i] = clip[i];
    if (!cut(&x, &y, &w, &h))
        w = h = 0, x = clip[0], y = clip[1];
    clip[0] = (int16_t) x;
    clip[1] = (int16_t) y;
    clip[2] = (int16_t) (x + w);
    clip[3] = (int16_t) (y + h);
}
static void pop_clip(const int16_t *save)
{
    for (int i = 0; i < 4; i++)
        clip[i] = save[i];
}

/* ------------------------------------------------------ damage and hits */
/* Damage that moves no hit box: a press highlight, the focus mark, the
 * bands of a wipe or of a list drag (whose start was an inval()). */
static void dirty(int x, int y, int w, int h)
{
    int x1 = x + w, y1 = y + h;
    if (x < 0)
        x = 0;
    if (y < 0)
        y = 0;
    if (x1 > CP_W)
        x1 = CP_W;
    if (y1 > CP_H)
        y1 = CP_H;
    if (x >= x1 || y >= y1)
        return;
    int best = -1;
    long grow = 0;
    for (unsigned i = 0; i < ndamage; i++) {
        int16_t *d = damage[i];
        int ux0 = x < d[0] ? x : d[0], uy0 = y < d[1] ? y : d[1];
        int ux1 = x1 > d[2] ? x1 : d[2], uy1 = y1 > d[3] ? y1 : d[3];
        long g = (long) (ux1 - ux0) * (uy1 - uy0) -
                 (long) (d[2] - d[0]) * (d[3] - d[1]) -
                 (long) (x1 - x) * (y1 - y);
        // Merge only when the union is smaller than both areas together (they
        // overlap enough); otherwise keep them apart: overlapping rectangles
        // are simply both repainted, and touching ones can each still be
        // covered by a single opaque fill. When the list is full, the cheapest
        // union is taken.
        if (best < 0 || g < grow) {
            best = (int) i;
            grow = g;
        }
    }
    if (best < 0 || (grow >= 0 && ndamage < MAX_DAMAGE)) {
        int16_t *d = damage[ndamage++];
        d[0] = (int16_t) x;
        d[1] = (int16_t) y;
        d[2] = (int16_t) x1;
        d[3] = (int16_t) y1;
        return;
    }
    int16_t *d = damage[best];
    if (x < d[0])
        d[0] = (int16_t) x;
    if (y < d[1])
        d[1] = (int16_t) y;
    if (x1 > d[2])
        d[2] = (int16_t) x1;
    if (y1 > d[3])
        d[3] = (int16_t) y1;
}
/* Any other change of the picture may have moved the hit boxes too, so
 * they are replayed (relayout) before they are next used. */
static uint8_t stale;
static void inval(int x, int y, int w, int h)
{
    stale = 1;
    dirty(x, y, w, h);
}
void cp_invalidate(void)
{
    ndamage = 0;
    inval(0, 0, CP_W, CP_H);
}
static void inval_content(void)
{
    inval(0, 18, CP_W, NAVY - 18);
}
/* Every replay numbers the hit boxes identically, so the pressed button is
 * known by its index as well as its action, even if the action recurs. */
static void hit(int x, int y, int w, int h, unsigned action)
{
    if (y < hit_y0) {
        h -= hit_y0 - y;
        y = hit_y0;
    }
    if (y + h > hit_y1)
        h = hit_y1 - y;
    if (h <= 0)
        return;
    // MAX_BUTTONS covers the busiest screen (the contact form: 41 boxes).
    CP_ASSERT(hit_seq < MAX_BUTTONS);
    if (hit_seq >= MAX_BUTTONS)
        return;
    if (layout)
        buttons[button_count++] =
            (button) {(uint8_t) x, (uint8_t) w,  (uint8_t) h,
                      0,           (uint16_t) y, (uint16_t) action};
    hit_seq++;
}
/* Is the button whose hit() was just called pressed? */
static int down(unsigned act)
{
    return cp.pressed && !cp.dragging && cp.press_action == act &&
           cp.press_index + 1 == hit_seq;
}
static void
key(int x, int y, int w, int h, const char *s, unsigned act, uint16_t c)
{
    hit(x, y, w, h, act);
    if (!vis(x, y, w, h))
        return;
    roundrect(x, y, w, h, down(act) ? shade(c) : c);
    center(x, y + (h - 8) / 2, w, s, WHITE, 1);
}

/* Everything above is the hot clipping and damage layer, compiled as the
 * build says (-O2). The scene description and application logic below run
 * once per replay or event and are optimized for size instead. Measured on
 * the journey: all of it at -O2 costs 11.7K text for 1.3% (full) and 6.5%
 * (partial) faster updates; only the scene functions at -O2, 4.2K for
 * 1.4% and 0.8%. Neither is worth it while every budget holds. */
#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC optimize("Os")
#endif

/* ------------------------------------------------------------ the scene */
static uint16_t wall_rgb(unsigned w, int y, int delta)
{
    const uint8_t *b = wall_base[cp.theme][w % 3];
    const int8_t *k = wall_slope[cp.theme][w % 3];
    int c[3];
    for (int i = 0; i < 3; i++) {
        c[i] = b[i] + (y * k[i]) / 64 + delta;
        c[i] = c[i] < 0 ? 0 : c[i] > 255 ? 255 : c[i];
    }
    return RGB(c[0], c[1], c[2]);
}
/* Gradient rows that quantize to the same RGB565 color share one fill.
 * The runs (at most 21 for the six gradients) are cached per wallpaper. */
#define WALL_RUNS 24
static uint16_t wall_end[WALL_RUNS], wall_color[WALL_RUNS];
static uint8_t wall_key, wall_runs;
static void wallpaper(int y0, int y1)
{
    unsigned key = cp.theme * 3u + cp.wallpaper % 3u + 1;
    if (wall_key != key) {
        wall_key = (uint8_t) key;
        wall_runs = 0;
        for (int y = 18; y < CP_H; y++) {
            uint16_t c = wall_rgb(cp.wallpaper, y, 0);
            if (!wall_runs ||
                (wall_color[wall_runs - 1] != c && wall_runs < WALL_RUNS))
                wall_color[wall_runs++] = c;
            wall_end[wall_runs - 1] = (uint16_t) (y + 1);
        }
    }
    int top = y0 > clip[1] ? y0 : clip[1], end = y1 < clip[3] ? y1 : clip[3];
    for (int i = 0, y = 18; i < wall_runs && y < end; y = wall_end[i++]) {
        int a = y > top ? y : top, b = wall_end[i] < end ? wall_end[i] : end;
        if (a < b)
            fill(0, a, CP_W, b - a, wall_color[i]);
    }
    circle(220, 100, 60, wall_rgb(cp.wallpaper, 100, cp.theme ? 18 : -22));
    circle(35, 220, 75, wall_rgb(cp.wallpaper, 220, cp.theme ? 10 : -35));
}
static void statusbar(void)
{
    if (!vis(0, 0, CP_W, 18))
        return;
    char s[12];
    fill(0, 0, CP_W, 18, bar);
    for (int i = 0; i < 4; i++)
        fill(6 + i * 4, 10 - i * 2, 3, 4 + i * 2, i < 3 ? WHITE : muted);
    text(26, 5, "MYCPU", WHITE, 1);
    hhmm(s, cp.minute);
    text(105, 5, s, WHITE, 1);
    if (cp.playing && cp.screen != CP_MUSIC) {
        glyph(142, 5, '>', ACCENT, 1);
        mmss(s, cp.position);
        text(148, 5, s, WHITE, 1);
    }
    percent(s, cp.battery);
    right(204, 5, s, WHITE, 1);
    fill(206, 4, 22, 10, WHITE);
    fill(208, 6, 18, 6, bar);
    fill(208, 6, (18 * cp.battery + 99) / 100, 6,
         cp.battery <= 20 ? RED : ACCENT);
    fill(228, 7, 2, 4, WHITE);
}
static void inval_clock(void)
{
    inval(105, 5, 30, 8);
    if (cp.screen == CP_LOCK)
        inval(0, 70, CP_W, 50);
    if (cp.screen == CP_HOME && cp.selected)
        inval(20, 40, 200, 50);
}
static void navbar(void)
{
    static const char *const tabs[] = {"< BACK", "HOME", "DIALER", "CONTACTS"};
    int active = cp.screen == CP_HOME       ? 1
                 : cp.screen == CP_PHONE    ? 2
                 : cp.screen == CP_CONTACTS ? 3
                                            : -1;
    if (vis(0, NAVY, CP_W, 20))
        fill(0, NAVY, CP_W, 20, cp.theme ? INK : RGB(142, 177, 164));
    for (int i = 0; i < 4; i++) {
        hit(i * 60, NAVY, 60, 20, ACT(K_NAV, i));
        if (!vis(i * 60, NAVY, 60, 20))
            continue;
        if (down(ACT(K_NAV, i)))
            fill(i * 60, NAVY, 60, 20, shade(bar));
        if (i == active)
            fill(i * 60 + 12, NAVY, 36, 2, ACCENT);
        center(i * 60, NAVY + 7, 60, tabs[i], i == active ? ACCENT : WHITE, 1);
    }
}
static void title(const char *s)
{
    text(12, 24, s, fg, 2);
}
static void icon(unsigned id, int x, int y)
{
    if (!vis(x, y, 37, 38))
        return;
    roundrect(x + 1, y + 2, 36, 36,
              cp.theme ? RGB(8, 12, 22) : RGB(125, 165, 154));
    roundrect(x, y, 36, 35, colors[id]);
    x += 6;
    y += 6;
    switch (id) {
    case 0:
        line(x + 3, y + 3, x + 3, y + 14, WHITE);
        line(x + 4, y + 14, x + 16, y + 20, WHITE);
        fill(x, y, 7, 7, WHITE);
        fill(x + 15, y + 15, 7, 7, WHITE);
        break;
    case 1:
        for (int i = 0; i < 3; i++) {
            fill(x, y + i * 8, 4, 5, WHITE);
            fill(x + 7, y + i * 8, 15, 5, WHITE);
        }
        break;
    case 2:
        roundrect(x, y, 24, 17, WHITE);
        line(x + 4, y + 16, x + 1, y + 22, WHITE);
        for (int i = 0; i < 3; i++)
            circle(x + 5 + i * 7, y + 8, 1, colors[id]);
        break;
    case 3:
        fill(x, y, 23, 24, WHITE);
        fill(x + 3, y + 3, 17, 5, colors[id]);
        for (int j = 0; j < 2; j++)
            for (int i = 0; i < 3; i++)
                fill(x + 3 + i * 6, y + 12 + j * 6, 4, 3, colors[id]);
        break;
    case 4:
        fill(x + 7, y + 1, 3, 18, WHITE);
        fill(x + 19, y, 3, 16, WHITE);
        fill(x + 7, y, 15, 4, WHITE);
        circle(x + 5, y + 19, 4, WHITE);
        circle(x + 17, y + 16, 4, WHITE);
        break;
    case 5:
        fill(x, y + 2, 24, 20, WHITE);
        fill(x + 2, y + 4, 20, 16, colors[id]);
        line(x + 3, y + 18, x + 10, y + 10, WHITE);
        line(x + 10, y + 10, x + 19, y + 18, WHITE);
        circle(x + 17, y + 8, 2, WHITE);
        break;
    case 6:
        roundrect(x, y + 4, 25, 18, WHITE);
        fill(x + 5, y, 10, 5, WHITE);
        circle(x + 13, y + 13, 7, colors[id]);
        circle(x + 13, y + 13, 4, WHITE);
        break;
    case 7:
        circle(x + 12, y + 12, 10, WHITE);
        for (int i = 0; i < 4; i++) {
            fill(x + 9, y - 1 + i * 8, 6, 4, WHITE);
            fill(x - 1 + i * 8, y + 9, 4, 6, WHITE);
        }
        circle(x + 12, y + 12, 4, colors[id]);
        break;
    case 8:
        line(x + 2, y + 6, x + 20, y + 6, WHITE);
        line(x + 20, y + 6, x + 16, y + 2, WHITE);
        line(x + 20, y + 6, x + 16, y + 10, WHITE);
        line(x + 20, y + 18, x + 2, y + 18, WHITE);
        line(x + 2, y + 18, x + 6, y + 14, WHITE);
        break;
    case 9:
        for (int i = 0; i < 5; i++)
            fill(x + (i % 3) * 7, y + (i / 3) * 8, 8, 8, WHITE);
        break;
    case 10:
        fill(x, y, 3, 14, WHITE);
        fill(x + 21, y + 10, 3, 14, WHITE);
        circle(x + 12, y + 12, 3, WHITE);
        break;
    default:
        for (int i = 0; i < 4; i++)
            fill(x + (i % 3) * 8, y + (i / 3) * 8, 7, 7, WHITE);
        break;
    }
}
static unsigned missed_calls(void)
{
    unsigned n = 0;
    for (unsigned i = 0; i < N_CALLS; i++)
        n += call_dir[i] == 2;
    return n;
}
static void card(int x, int y, int w, int h, unsigned act)
{
    hit(x, y, w, h, act);
    roundrect(x, y, w, h, down(act) ? shade(panel) : panel);
}
static void home(void)
{
    wallpaper(18, NAVY);
    if (!cp.selected) {
        for (unsigned i = 0; i < 12; i++) {
            int x = 22 + (i % 3) * 80, y = 34 + (i / 3) * 65;
            hit(x - 15, y - 2, 70, 58, ACT(K_APP, i));
            if (!vis(x - 15, y - 2, 70, 58))
                continue;
            if (down(ACT(K_APP, i)))
                roundrect(x - 14, y - 2, 68, 58,
                          cp.theme ? RGB(45, 60, 82) : RGB(150, 190, 175));
            icon(i, x, y);
            center(x - 20, y + 40, 76, names[i], fg, 1);
        }
    } else {
        char s[32];
        card(12, 30, 216, 70, ACT(K_APP, 7));
        hhmm(s, cp.minute);
        center(12, 40, 216, s, fg, 4);
        date(s, sizeof s);
        center(12, 82, 216, s, muted, 1);
        card(12, 108, 156, 60, ACT(K_APP, 4));
        text(22, 116, "NOW PLAYING", muted, 1);
        textn(22, 132, tracks[cp.track], 23, fg, 1);
        textn(22, 148, artists[cp.track], 23, muted, 1);
        key(176, 108, 52, 60, cp.playing ? "PAUSE" : "PLAY", ACT(K_CMD, C_PLAY),
            ACCENT);
        card(12, 176, 104, 56, ACT(K_APP, 8));
        num(22, 186, (int32_t) missed_calls(), RED, 2);
        text(22, 210, "MISSED CALLS", muted, 1);
        card(124, 176, 104, 56, ACT(K_APP, 2));
        num(134, 186, N_THREADS, colors[2], 2);
        text(134, 210, "CONVERSATIONS", muted, 1);
        card(12, 240, 216, 40, ACT(K_APP, 9));
        text(22, 256, "PLAY A GAME  >", fg, 1);
    }
    // Page indicator: the active page is a pill, tap a dot to switch.
    for (int p = 0; p < 2; p++) {
        int x = p == 0 ? 106 : (cp.selected ? 120 : 128);
        hit(x - 6, 284, 26, 16, ACT(K_CMD, C_PAGE0 + p));
        fill(x, 291, p == cp.selected ? 14 : 6, 6,
             p == cp.selected ? fg : muted);
    }
}
static uint16_t slider_panel(void)
{
    return cp.theme ? RGB(22, 37, 59) : RGB(150, 191, 178);
}
static void lock_slider(void)
{
    fill(8, 248, 224, 44, slider_panel());
    roundrect(12, 258, 216, 24,
              cp.theme ? RGB(45, 60, 82) : RGB(107, 161, 144));
    center(40, 266, 170, "SLIDE TO UNLOCK  >", WHITE, 1);
    // One opaque fill with clipped corners: repainting the knob skips the
    // layers below it.
    int x = 14 + cp.slide;
    fill(x, 256, 36, 28, cp.grab == G_KNOB ? shade(ACCENT) : ACCENT);
    for (int i = 0; i < 4; i++)
        fill(x + (i & 1) * 35, 256 + (i >> 1) * 27, 1, 1, slider_panel());
    center(x, 266, 36, ">", WHITE, 1);
}
static void lock_screen(void)
{
    char s[32];
    statusbar();
    wallpaper(18, CP_H);
    hhmm(s, cp.minute);
    center(0, 70, CP_W, s, fg, 4);
    date(s, sizeof s);
    center(0, 112, CP_W, s, muted, 1);
    center(0, 190, CP_W, "MYCPU  /  CELLPHONE", fg, 1);
    lock_slider();
}

/* Lists scroll in pixels; rows outside the viewport are culled. */
static int has_thread(unsigned who);
static int list_h(void)
{
    return cp.screen == CP_CONTACTS ? 48 : cp.screen == CP_MESSAGES ? 40 : 34;
}
static unsigned threads(unsigned *out)
{
    unsigned n = 0;
    for (unsigned i = 0; i < contacts_n(); i++)
        if (has_thread(i)) {
            if (out)
                out[n] = i;
            n++;
        }
    return n;
}
static unsigned list_n(void)
{
    switch (cp.screen) {
    case CP_CONTACTS:
        return contacts_n();
    case CP_CALLLOG:
        return N_CALLS;
    case CP_MESSAGES:
        return threads(0);
    default:
        return N_TRACKS;
    }
}
/* Lists scroll by whole rows, so rows keep their places and a scroll only
 * changes their texts and avatars. */
static int list_max(void)
{
    int h = list_h(), m = (int) list_n() * h - (BOTTOM - TOP);
    return m > 0 ? (m + h - 1) / h * h : 0;
}
static int is_list(void)
{
    return cp.screen == CP_CONTACTS || cp.screen == CP_CALLLOG ||
           cp.screen == CP_MESSAGES || cp.screen == CP_TRACKLIST;
}
static int thread_of(unsigned who)
{
    for (unsigned t = 0; t < N_THREADS; t++)
        if (sms_contact[t] == who)
            return (int) t;
    return -1;
}
static unsigned sent_slot(unsigned j)
{
    return (cp.sent_head + CP_SENT - cp.sent_count + j) % CP_SENT;
}
static int has_thread(unsigned who)
{
    if (thread_of(who) >= 0)
        return 1;
    for (unsigned j = 0; j < cp.sent_count; j++)
        if (cp.sent[sent_slot(j)].who == who)
            return 1;
    return 0;
}
/* Message k of a conversation: stock thread first, then what was sent. */
static unsigned messages(unsigned who)
{
    int t = thread_of(who);
    unsigned n = t < 0 ? 0 : sms_first[t + 1] - sms_first[t];
    for (unsigned j = 0; j < cp.sent_count; j++)
        n += cp.sent[sent_slot(j)].who == who;
    return n;
}
static const char *message(unsigned who, unsigned k, int *out)
{
    int t = thread_of(who);
    unsigned stock = t < 0 ? 0 : sms_first[t + 1] - sms_first[t];
    if (k < stock) {
        *out = (int) (sms_out >> (sms_first[t] + k)) & 1;
        return sms_text[sms_first[t] + k];
    }
    k -= stock;
    *out = 1;
    for (unsigned j = 0; j < cp.sent_count; j++)
        if (cp.sent[sent_slot(j)].who == who && !k--)
            return cp.sent[sent_slot(j)].text;
    return "";
}
static void avatar(int x, int y, int r, const char *name, uint16_t c)
{
    char s[2] = {name[0], 0};
    circle(x, y, r, c);
    center(x - r, y - 4, 2 * r + 1, s, WHITE, 1);
}
static void call_arrow(int x, int y, unsigned dir)
{
    uint16_t c = dir == 2 ? RED : dir ? BLUE : ACCENT;
    if (dir == 1) {
        line(x, y + 10, x + 10, y, c);
        fill(x + 5, y, 6, 2, c);
        fill(x + 9, y, 2, 6, c);
    } else {
        line(x + 10, y, x, y + 10, c);
        fill(x, y + 9, 6, 2, c);
        fill(x, y + 5, 2, 6, c);
    }
}
static void row(unsigned i, int y, int h)
{
    int out;
    char s[8];
    unsigned act = ACT(K_ITEM, i), who = i;
    if (cp.screen == CP_CONTACTS) {
        // A-Z sections: the letter heads the first contact that starts with it.
        who = cp.order[i];
        char letter[2] = {(char) upper((unsigned char) cname(who)[0]), 0};
        fill(0, y, CP_W, 14, panel);
        if (!i || upper((unsigned char) cname(cp.order[i - 1])[0]) !=
                      (unsigned char) letter[0])
            text(12, y + 3, letter, ACCENT, 1);
        y += 14;
        h -= 14;
    }
    if (cp.screen == CP_MESSAGES) {
        unsigned list[N_ALL];
        threads(list);
        who = list[i];
        act = ACT(K_ITEM, who);
    }
    hit(0, y, CP_W, h, act);
    if (!vis(0, y, CP_W, h))
        return;
    if (down(act))
        fill(0, y, CP_W, h, panel);
    fill(44, y + h - 1, 196, 1, panel);
    switch (cp.screen) {
    case CP_CONTACTS:
        avatar(24, y + 17, 12, cname(who), colors[who % 12]);
        text(46, y + 7, cname(who), fg, 1);
        text(46, y + 20, cphone(who), muted, 1);
        break;
    case CP_CALLLOG:
        call_arrow(14, y + 12, call_dir[i]);
        text(40, y + 7, call_name[i], call_dir[i] == 2 ? RED : fg, 1);
        text(40, y + 20, call_when[i], muted, 1);
        right(228, y + 13,
              call_dir[i] == 2 ? "MISSED"
              : call_dir[i]    ? "OUT"
                               : "IN",
              muted, 1);
        break;
    case CP_MESSAGES: {
        unsigned n = messages(who);
        int t = thread_of(who);
        const char *last = message(who, n - 1, &out);
        avatar(24, y + 19, 13, cname(who), colors[2]);
        text(46, y + 7, cname(who), fg, 1);
        right(232, y + 7,
              (int) n > (t < 0 ? 0 : sms_first[t + 1] - sms_first[t])
                  ? "NOW"
                  : sms_when[t],
              muted, 1);
        textn(46, y + 22, last, 30, muted, 1);
        break;
    }
    default:
        if (vis(10, y + 13, 12, 8))
            num(10, y + 13, (int32_t) i + 1, muted, 1);
        text(34, y + 7, tracks[i], i == cp.track ? ACCENT : fg, 1);
        text(34, y + 20, artists[i], muted, 1);
        if (vis(196, y + 13, 36, 8)) {
            mmss(s, durations[i]);
            right(232, y + 13, s, muted, 1);
        }
    }
}
static void list(void)
{
    int16_t save[4];
    int h = list_h(), n = (int) list_n(), max = list_max();
    push_clip(0, TOP, CP_W, BOTTOM - TOP, save);
    hit_y0 = TOP;
    hit_y1 = BOTTOM;
    for (int i = 0; i < n; i++) {
        int y = TOP + i * h - cp.scroll;
        if (y + h <= TOP)
            continue;
        if (y >= BOTTOM)
            break;
        row((unsigned) i, y, h);
    }
    if (max) {
        int len = (BOTTOM - TOP) * (BOTTOM - TOP) / (n * h);
        fill(236, TOP + cp.scroll * (BOTTOM - TOP - len) / max, 3, len, muted);
    }
    hit_y0 = 0;
    hit_y1 = CP_H;
    pop_clip(save);
}

static void dialer(void)
{
    roundrect(12, 26, 216, 44, panel);
    unsigned n = length(cp.dial);
    if (!n)
        center(12, 44, 216, "ENTER NUMBER", muted, 1);
    else if (n <= 17)
        center(12, 40, 216, cp.dial, fg, 2);
    else
        center(12, 44, 216, cp.dial, fg, 1);
    const char *digits = "123456789*0#";
    for (int i = 0; i < 12; i++) {
        char s[2] = {digits[i], 0};
        int x = 16 + (i % 3) * 72, y = 78 + (i / 3) * 44;
        unsigned act = ACT(K_CHAR, digits[i]);
        hit(x, y, 64, 38, act);
        if (!vis(x, y, 64, 38))
            continue;
        roundrect(x, y, 64, 38,
                  down(act) ? shade(RGB(57, 90, 111)) : RGB(57, 90, 111));
        center(x, y + 6, 64, s, WHITE, 2);
        center(x, y + 26, 64, dial_letters[i], RGB(170, 190, 205), 1);
    }
    key(16, 256, 100, 36, "CALL", ACT(K_CMD, C_CALL), ACCENT);
    key(124, 256, 100, 36, "DELETE", ACT(K_CMD, C_DEL), RED);
}
static void contact(void)
{
    unsigned n = cp.selected < contacts_n() ? cp.selected : 0;
    avatar(120, 62, 24, cname(n), colors[n % 12]);
    center(0, 96, CP_W, cname(n), fg, 2);
    roundrect(12, 122, 216, 62, panel);
    text(22, 130, "MOBILE", muted, 1);
    text(22, 142, cphone(n), fg, 1);
    text(22, 158, "EMAIL", muted, 1);
    text(22, 170, cemail(n), fg, 1);
    key(12, 196, 100, 36, "CALL", ACT(K_CMD, C_CALL), ACCENT);
    key(128, 196, 100, 36, "MESSAGE", ACT(K_CMD, C_MESSAGE), BLUE);
}
static const char *call_number(void)
{
    return cp.call_who == 255   ? cp.dial
           : cp.call_who >= 128 ? call_phone[(cp.call_who - 128) % N_CALLS]
                                : cphone(cp.call_who);
}
static const char *call_title(void)
{
    return cp.call_who == 255   ? "Calling"
           : cp.call_who >= 128 ? call_name[(cp.call_who - 128) % N_CALLS]
                                : cname(cp.call_who);
}
static void call(void)
{
    char s[12]; /* "1092:15" at most */
    avatar(120, 76, 28, call_title(), colors[0]);
    center(0, 118, CP_W, call_title(), fg, 2);
    center(0, 142, CP_W, call_number(), muted, 1);
    if (cp.call_secs < 2)
        center(0, 164, CP_W, "CALLING...", muted, 1);
    else {
        mmss(s, cp.call_secs);
        center(0, 164, CP_W, s, ACCENT, 1);
    }
    key(42, 230, 156, 40, "END CALL", ACT(K_CMD, C_END), RED);
}

/* Bubbles wrap at word boundaries, 26 characters per line. */
static unsigned next_line(const char *s)
{
    unsigned n = length(s), cut = 26;
    if (n <= 26)
        return n;
    while (cut && s[cut] != ' ')
        cut--;
    return cut ? cut : 26;
}
static void keyboard(const char *send);
static int chat_bottom(void)
{
    return cp.kbd ? 176 : 270;
}
static int input_y(void)
{
    return cp.kbd ? 180 : 274;
}
static void chat(void)
{
    unsigned who = cp.selected, n = messages(who);
    int16_t save[4];
    title(cname(who));
    push_clip(0, TOP, CP_W, chat_bottom() - TOP, save);
    int y = chat_bottom();
    for (unsigned k = n; k-- > 0 && y > TOP;) {
        int out;
        const char *s = message(who, k, &out), *p = s;
        unsigned lines = 0, wide = 0, len[4];
        while (*p && lines < 4) {
            unsigned l = next_line(p);
            len[lines++] = l;
            wide = l > wide ? l : wide;
            p += l;
            while (*p == ' ')
                p++;
        }
        int h = (int) lines * 10 + 6, w = (int) wide * 6 + 10;
        int x = out ? 232 - w : 8;
        y -= h;
        if (vis(x, y, w, h)) {
            roundrect(x, y, w, h, out ? RGB(16, 150, 110) : panel);
            p = s;
            for (unsigned i = 0; i < lines; i++) {
                textn(x + 5, y + 4 + (int) i * 10, p, len[i], out ? WHITE : fg,
                      1);
                p += len[i];
                while (*p == ' ')
                    p++;
            }
        }
        y -= 4;
    }
    pop_clip(save);
    int iy = input_y();
    unsigned dn = length(cp.draft);
    hit(8, iy, 176, 20, ACT(K_CMD, C_KBD_OPEN));
    roundrect(8, iy, 176, 20, panel);
    if (dn) {
        unsigned skip = dn > 27 ? dn - 27 : 0;
        text(14, iy + 6, cp.draft + skip, fg, 1);
        glyph(14 + (int) (dn - skip) * 6, iy + 6, '_', ACCENT, 1);
    } else
        text(14, iy + 6, "Type a message", muted, 1);
    key(188, iy, 44, 20, "SEND", ACT(K_CMD, C_SEND), ACCENT);
    keyboard("SEND");
}
/* The soft keyboard of the reference demo, shared by chat and forms. */
static void keyboard(const char *send)
{
    if (!cp.kbd)
        return;
    const char *const *rows = kbd_rows[cp.kbd_layer % 3];
    for (int r = 0; r < 3; r++) {
        const char *keys = rows[r];
        int x0 = r == 0 ? 1 : r == 1 ? 13 : 37;
        for (int i = 0; keys[i]; i++) {
            char s[2] = {keys[i], 0};
            key(x0 + i * 24, 204 + r * 24, 22, 22, s, ACT(K_CHAR, keys[i]),
                KEYC);
        }
    }
    if (cp.kbd_layer == 2)
        key(1, 252, 34, 22, "*", ACT(K_CHAR, '*'), KEYC);
    else
        key(1, 252, 34, 22, cp.kbd_layer ? "abc" : "ABC", ACT(K_CMD, C_SHIFT),
            cp.kbd_layer ? ACCENT : INK);
    key(205, 252, 34, 22, "DEL", ACT(K_CMD, C_DEL), RED);
    key(1, 276, 40, 22, cp.kbd_layer == 2 ? "abc" : "123", ACT(K_CMD, C_LAYER),
        INK);
    key(43, 276, 110, 22, "SPACE", ACT(K_CHAR, ' '), KEYC);
    key(155, 276, 48, 22, send, ACT(K_CMD, C_SEND), ACCENT);
    key(205, 276, 34, 22, "X", ACT(K_CMD, C_KBD_CLOSE), INK);
}

/* New contact form: three fields edited in place in the next free slot. */
static char *form_field(unsigned f)
{
    unsigned slot = cp.users % CP_USERS;
    return f == 0   ? cp.user[slot].name
           : f == 1 ? cp.user[slot].phone
                    : cp.user[slot].email;
}
static unsigned form_size(unsigned f)
{
    return f == 0   ? sizeof cp.user[0].name
           : f == 1 ? sizeof cp.user[0].phone
                    : sizeof cp.user[0].email;
}
static int field_y(unsigned f)
{
    return 56 + (int) f * 36;
}
static void form(void)
{
    static const char *const labels[] = {"NAME", "PHONE", "EMAIL"};
    title("New Contact");
    for (unsigned f = 0; f < 3; f++) {
        int y = field_y(f);
        const char *v = form_field(f);
        text(12, y - 10, labels[f], muted, 1);
        hit(12, y, 216, 20, ACT(K_CMD, C_FIELD0 + f));
        roundrect(12, y, 216, 20, panel);
        text(16, y + 6, v, fg, 1);
        if (f == cp.field) {
            fill(14, y + 17, 212, 2, ACCENT);
            glyph(16 + 6 * (int) length(v), y + 6, '_', ACCENT, 1);
        }
    }
    key(12, 160, 100, 24, "CANCEL", ACT(K_CMD, C_CANCEL), KEYC);
    key(128, 160, 100, 24, "SAVE", ACT(K_CMD, C_SAVE), ACCENT);
    if (cp.form_error)
        text(12, 190, "A NAME IS REQUIRED", RED, 1);
    keyboard("NEXT");
}
static void field_inval(unsigned f)
{
    inval(12, field_y(f), 216, 20);
}
static int name_before(unsigned a, unsigned b)
{
    const char *x = cname(a), *y = cname(b);
    for (; *x && upper((unsigned char) *x) == upper((unsigned char) *y);
         x++, y++)
        ;
    return upper((unsigned char) *x) < upper((unsigned char) *y);
}

/* Calculator: three decimal digits of fixed point, no floating point. */
static const char calc_keys[] = "Cn%/789*456-123+0.=";
static void calc(void)
{
    roundrect(8, 26, 224, 54, panel);
    if (cp.op) {
        char s[2] = {cp.op == '*' ? 'x' : cp.op, 0};
        text(16, 34, s, muted, 2);
    }
    const char *s = cp.error ? "ERROR" : cp.calc;
    int sc = length(s) <= 11 ? 3 : 2;
    right(226, 44, s, cp.error ? RED : fg, sc);
    for (int i = 0; i < 19; i++) {
        int slot = i < 17 ? i : i + 1, c = slot % 4, r = slot / 4;
        char k = calc_keys[i], label[4] = {k, 0, 0, 0};
        if (k == 'n')
            copy(label, "+/-", 4);
        if (k == '*')
            label[0] = 'x';
        unsigned act = ACT(K_CHAR, k);
        int x = 8 + c * 57, y = 88 + r * 42, w = k == '0' ? 109 : 52;
        uint16_t color = c == 3 || k == '=' ? colors[3]
                         : r == 0           ? RGB(100, 116, 139)
                                            : KEYC;
        hit(x, y, w, 36, act);
        if (!vis(x, y, w, 36))
            continue;
        roundrect(x, y, w, 36, down(act) ? shade(color) : color);
        center(x, y + 10, w, label, WHITE, 2);
    }
}
static void inval_calc(void)
{
    inval(12, 31, 216, 44);
}

static int eq_height(unsigned i, unsigned phase, unsigned on)
{
    return on ? 4 + (int) ((phase * 7 + i * 13) % 26) : 4;
}
static int progress(void)
{
    return (int) cp.position * 200 / durations[cp.track % N_TRACKS];
}
static void music(void)
{
    char s[8];
    roundrect(70, 26, 100, 96, colors[4]);
    icon(4, 102, 56);
    key(184, 26, 48, 22, "LIST", ACT(K_CMD, C_LIST), colors[4]);
    const char *t = tracks[cp.track];
    if (length(t) <= 18)
        center(0, 130, CP_W, t, fg, 2);
    else
        center(0, 134, CP_W, t, fg, 1);
    center(0, 152, CP_W, artists[cp.track], muted, 1);
    int w = progress();
    fill(20, 172, 200, 4, panel);
    fill(20, 172, w, 4, ACCENT);
    fill(18 + w, 169, 5, 10, ACCENT);
    mmss(s, cp.position);
    text(20, 184, s, muted, 1);
    mmss(s, durations[cp.track]);
    right(220, 184, s, muted, 1);
    key(20, 200, 56, 32, "PREV", ACT(K_CMD, C_PREV), colors[4]);
    key(92, 200, 56, 32, cp.playing ? "PAUSE" : "PLAY", ACT(K_CMD, C_PLAY),
        ACCENT);
    key(164, 200, 56, 32, "NEXT", ACT(K_CMD, C_NEXT), colors[4]);
    for (unsigned i = 0; i < 18; i++) {
        int h = eq_height(i, cp.eq, cp.playing);
        fill(17 + (int) i * 12, 292 - h, 9, h, colors[4]);
    }
}

static void photos_grid(void)
{
    for (int i = 0; i < 6; i++) {
        int x = 12 + (i % 2) * 120, y = 46 + (i / 2) * 82;
        hit(x, y, 96, 64, ACT(K_ITEM, i));
        // The grid loads one photo per tick; the rest show a placeholder.
        if (cp.trans_dir == D_PHOTOS && i >= 6 - cp.trans)
            fill(x, y, 96, 64, panel);
        else
            bitmap(x, y, &photos[i][0][0], 2);
        if (down(ACT(K_ITEM, i)))
            for (int b = 0; b < 4; b++)
                fill(b == 1 ? x + 93 : x, b == 2 ? y + 61 : y, b & 1 ? 3 : 96,
                     b & 1 ? 64 : 3, ACCENT);
        num(x + 3, y + 68, i + 1, muted, 1);
    }
}
static void photo_view(void)
{
    char s[12];
    number(s, cp.selected % 6 + 1);
    copy(s + length(s), " / 6", 6);
    center(0, 30, CP_W, s, fg, 1);
    bitmap(24, 60, &photos[cp.selected % 6][0][0], 4);
    center(0, 204, CP_W, "<  SWIPE  >", muted, 1);
    key(12, 250, 100, 30, "< PREVIOUS", ACT(K_CMD, C_PREV), colors[5]);
    key(128, 250, 100, 30, "NEXT >", ACT(K_CMD, C_NEXT), colors[5]);
}
static void mountain(int cx, int top, int bottom, int slope, uint16_t c)
{
    for (int y = top; y < bottom; y++) {
        int half = (y - top) * slope / 4;
        fill(cx - half, y, 2 * half + 1, 1, c);
    }
}
/* Sky gradient row k (0..213): k * {113,155,191} / 214, exactly, as
 * multiply and shift. */
static uint16_t sky(int k)
{
    return RGB(144 - (k * 34605 >> 16), 202 - (k * 47466 >> 16),
               249 - (k * 58492 >> 16));
}
static void camera(void)
{
    int16_t save[4];
    fill(0, 18, CP_W, NAVY - 18, BLACK);
    push_clip(4, 22, 232, 214, save);
    if (vis(4, 22, 232, 214)) {
        for (int y = clip[1]; y < clip[3];) {
            int k = y - 22;
            uint16_t c = sky(k);
            int n = 1;
            while (y + n < clip[3] && sky(k + n) == c)
                n++;
            fill(4, y, 232, n, c);
            y += n;
        }
        circle(184, 62, 14, RGB(255, 220, 122));
        mountain(70, 120, 200, 7, RGB(96, 125, 139));
        mountain(170, 140, 200, 9, RGB(69, 90, 100));
        fill(4, 200, 232, 36, RGB(44, 97, 91));
        roundrect(10, 28, 28, 14, RGB(38, 50, 56));
        text(15, 31, "HDR", WHITE, 1);
        roundrect(42, 28, 40, 14, RGB(38, 50, 56));
        text(47, 31, "12 MP", WHITE, 1);
        text(186, 31, "EV -0.3", WHITE, 1);
        for (int i = 0; i < 4; i++) {
            int x = i & 1 ? 138 : 98, y = i & 2 ? 146 : 110;
            fill(i & 1 ? x - 9 : x, y, 10, 2, WHITE);
            fill(i & 1 ? x - 1 : x, i & 2 ? y - 8 : y, 2, 10, WHITE);
        }
        if (cp.shots) {
            char s[20] = "PHOTO ";
            number(s + 6, cp.shots);
            copy(s + length(s), " SAVED", 8);
            roundrect(60, 214, 120, 16, RGB(38, 50, 56));
            center(60, 218, 120, s, WHITE, 1);
        }
    }
    pop_clip(save);
    text(30, 264, "PHOTO", ACCENT, 1);
    text(180, 264, "VIDEO", muted, 1);
    hit(96, 244, 48, 48, ACT(K_CMD, C_CAPTURE));
    circle(120, 268, 22, WHITE);
    circle(120, 268, 18, BLACK);
    circle(120, 268, 15, down(ACT(K_CMD, C_CAPTURE)) ? muted : WHITE);
}

static void level(int y, unsigned v, unsigned max, unsigned dn)
{
    char s[8];
    fill(16, y + 8, 208, 8, panel);
    fill(16, y + 8, 208 * (int) v / (int) max, 8, ACCENT);
    key(16, y + 24, 48, 28, "-", ACT(K_SET, dn), KEYC);
    key(176, y + 24, 48, 28, "+", ACT(K_SET, dn + 1), KEYC);
    percent(s, v * 100 / max);
    center(64, y + 34, 112, s, fg, 1);
}
static void settings(void)
{
    char s[8];
    for (unsigned i = 0; i < 6; i++) {
        int y = TOP + (int) i * 42;
        const char *sub = i == 0   ? (cp.theme ? "Dark theme" : "Olive theme")
                          : i == 1 ? "Volume"
                          : i == 2 ? wallpaper_names[cp.wallpaper % 3]
                          : i == 3 ? s
                          : i == 4 ? "MyCPU T5 / RGB565"
                                   : "Lock the phone now";
        if (i == 3)
            hhmm(s, cp.minute);
        hit(10, y, 220, 38, ACT(K_ITEM, i));
        if (!vis(10, y, 220, 38))
            continue;
        roundrect(10, y, 220, 38, down(ACT(K_ITEM, i)) ? shade(panel) : panel);
        circle(28, y + 19, 10, colors[(i * 5 + 7) % 12]);
        text(46, y + 7, settings_names[i], fg, 1);
        text(46, y + 22, sub, muted, 1);
        text(220, y + 15, ">", muted, 1);
    }
}
#ifdef __riscv
/* Linker symbols of the bare-metal (sim/cellphone/cellphone.ld) and the
 * kernel-hosted (riscv-app.ld, linked at 0x80000000) images; weak, so
 * whichever script is not in use leaves them null. */
extern char _bss_start[] __attribute__((weak)),
    _bss_end[] __attribute__((weak));
extern char __bss_start[] __attribute__((weak)), _end[] __attribute__((weak));
#endif
static void memory_row(int y, const char *label, uint32_t bytes, int known)
{
    char s[16];
    text(16, y, label, label[0] == ' ' ? muted : fg, 1);
    if (known) {
        copy(number(s, (int32_t) bytes), " B", 3);
    } else
        copy(s, "-", 2);
    right(224, y, s, label[0] == ' ' ? muted : fg, 1);
}
/* A census of the static memory, like the reference's mem_report. */
static void memory(void)
{
    uint32_t bss = 0, image = 0;
#ifdef __riscv
    if ((uintptr_t) _bss_end) {
        bss = (uint32_t) (_bss_end - _bss_start);
        image = (uint32_t) (uintptr_t) _bss_end; /* linked at 0 */
    } else if ((uintptr_t) _end) {
        bss = (uint32_t) (_end - __bss_start);
        image = (uint32_t) ((uintptr_t) _end - 0x80000000u);
    }
#endif
    memory_row(48, "UI STATE", sizeof cp, 1);
    memory_row(60, "  NAV HISTORY", sizeof cp.stack, 1);
    memory_row(72, "  MESSAGES", sizeof cp.sent + sizeof cp.draft, 1);
    memory_row(84, "  ADDED CONTACTS", sizeof cp.user, 1);
    memory_row(96, "  GAMES (UNION)", sizeof cp.g, 1);
    memory_row(110, "HIT BOXES", sizeof buttons, 1);
    memory_row(122, "DAMAGE LIST", sizeof damage, 1);
    memory_row(134, "WALLPAPER RUNS", sizeof wall_end + sizeof wall_color, 1);
    memory_row(146, "HEAP", 0, 1);
    memory_row(160, "FRAMEBUFFER", CP_W * CP_H * 2, 1);
    memory_row(172, "PHOTOS (8-BIT)", sizeof photos + sizeof photo_palette, 1);
    memory_row(186, "IMAGE BSS", bss, bss != 0);
    memory_row(198, "IMAGE + BSS", image, image != 0);
    center(0, 218, CP_W, "LINKER FIGURES: FIRMWARE ONLY", muted, 1);
}
static void setpage(void)
{
    unsigned p = cp.selected % 6;
    char s[8];
    title(p == 5 ? "Memory" : settings_names[p]);
    switch (p) {
    case 0:
        text(16, 52, "BRIGHTNESS", muted, 1);
        level(56,
              cp.grab == G_LEVEL ? (unsigned) cp.press_value : cp.brightness,
              10, S_BRIGHT_DN);
        text(16, 128, "THEME", muted, 1);
        key(16, 142, 100, 32, "OLIVE", ACT(K_SET, S_THEME0),
            cp.theme ? KEYC : ACCENT);
        key(124, 142, 100, 32, "DARK", ACT(K_SET, S_THEME1),
            cp.theme ? ACCENT : KEYC);
        break;
    case 1:
        text(16, 52, "VOLUME", muted, 1);
        level(56, cp.volume, 10, S_VOL_DN);
        center(0, 128, CP_W, cp.volume ? "RINGER ON" : "SILENT", fg, 1);
        break;
    case 2:
        text(16, 52, "CHOOSE WALLPAPER", muted, 1);
        for (unsigned i = 0; i < 3; i++) {
            int x = 16 + (int) i * 72;
            hit(x, 66, 64, 100, ACT(K_SET, S_WALL0 + i));
            if (cp.wallpaper == i)
                fill(x - 3, 63, 70, 106, ACCENT);
            for (int b = 0; b < 4; b++)
                fill(x, 66 + b * 25, 64, 25, wall_rgb(i, 18 + b * 90, 0));
            center(x, 176, 64, wallpaper_names[i], fg, 1);
        }
        break;
    case 3:
        hhmm(s, cp.minute);
        center(0, 60, CP_W, s, fg, 4);
        key(24, 112, 92, 32, "HOUR +", ACT(K_SET, S_HOUR_UP), KEYC);
        key(124, 112, 92, 32, "MIN +", ACT(K_SET, S_MIN_UP), KEYC);
        key(24, 152, 92, 32, "HOUR -", ACT(K_SET, S_HOUR_DN), KEYC);
        key(124, 152, 92, 32, "MIN -", ACT(K_SET, S_MIN_DN), KEYC);
        center(0, 200, CP_W, "SECONDS RESET WHEN SET", muted, 1);
        break;
    default:
        center(0, 60, CP_W, "MYCPU CELLPHONE 1.1", fg, 1);
        center(0, 80, CP_W, "QVGA 240x320 RGB565", muted, 1);
        center(0, 96, CP_W, "MYCPU T5 RV32IM", muted, 1);
        number(s, sizeof cp);
        center(0, 120, CP_W, s, fg, 2);
        center(0, 140, CP_W, "BYTES OF UI STATE, NO HEAP", muted, 1);
        center(0, 170, CP_W, "MIT LICENSE", muted, 1);
        key(60, 206, 120, 30, "MEMORY REPORT", ACT(K_CMD, C_MEMORY), KEYC);
        break;
    case 5:
        memory();
        break;
    }
}

/* Games share one court. */
static const uint16_t shapes[7][4] = {
    {0x00f0, 0x4444, 0x0f00, 0x2222}, {0x0066, 0x0066, 0x0066, 0x0066},
    {0x0072, 0x0262, 0x0270, 0x0232}, {0x0036, 0x0462, 0x0360, 0x0231},
    {0x0063, 0x0264, 0x0630, 0x0132}, {0x0071, 0x0226, 0x0470, 0x0322},
    {0x0074, 0x0622, 0x0170, 0x0223}};
static int food_x(void)
{
    return (int) ((cp.score * 7 + 13) % 22);
}
static int food_y(void)
{
    return (int) ((cp.score * 3 + 5) % 18);
}
static int opponent_y(void)
{
    int y = cp.g.pong.y - 10;
    return y < 51 ? 51 : y > 181 ? 181 : y;
}
static void game(void)
{
    roundrect(16, 51, 192, 160, INK);
    if (cp.screen == CP_SNAKE) {
        for (unsigned i = 0; i < cp.g.snake.len; i++) {
            unsigned p = cp.g.snake.body[i];
            fill(22 + (int) (p % 22) * 8, 57 + (int) (p / 22) * 8, 7, 7,
                 i ? ACCENT : WHITE);
        }
        fill(22 + food_x() * 8, 57 + food_y() * 8, 7, 7, RED);
    } else if (cp.screen == CP_PONG) {
        for (int y = 58; y < 200; y += 12)
            fill(109, y, 2, 6, panel);
        fill(24, cp.g.pong.paddle, 4, 30, ACCENT);
        fill(196, opponent_y(), 4, 30, WHITE);
        circle(cp.g.pong.x, cp.g.pong.y, 3, WHITE);
    } else {
        for (int y = 0; y < 14; y++)
            for (int x = 0; x < 10; x++)
                if (cp.g.tetris.board[y][x])
                    fill(57 + x * 10, 57 + y * 10, 9, 9,
                         tetris_colors[(cp.g.tetris.board[y][x] - 1) % 7]);
        unsigned bits = shapes[cp.g.tetris.piece % 7][cp.g.tetris.rot % 4];
        for (int i = 0; i < 16; i++)
            if (bits & (1u << i))
                fill(57 + (cp.g.tetris.x + i % 4) * 10,
                     57 + (cp.g.tetris.y + i / 4) * 10, 9, 9,
                     tetris_colors[cp.g.tetris.piece % 7]);
    }
    text(24, 225, "SCORE", muted, 1);
    num(68, 221, (int32_t) cp.score, fg, 2);
    text(24, 251, "ARROWS: MOVE", muted, 1);
    text(24, 265, "SPACE: PAUSE", muted, 1);
    if (cp.over)
        text(144, 265, "GAME OVER", RED, 1);
    else if (!cp.running)
        text(144, 265, "PAUSED", ACCENT, 1);
}

/* The focus underline stays inside its (possibly clipped) hit box. */
static int focus_y(const button *b)
{
    return b->y + (b->h > 2 ? b->h - 2 : 0);
}
static void paint(void)
{
    if (cp.screen == CP_LOCK) {
        lock_screen();
        return;
    }
    statusbar();
    if (cp.screen != CP_HOME)
        fill(0, 18, CP_W, NAVY - 18, bg);
    if (cp.screen >= CP_CONTACTS && cp.screen <= CP_TETRIS &&
        cp.screen != CP_CALC && cp.screen != CP_MUSIC && cp.screen != CP_CAMERA)
        title(names[cp.screen - CP_PHONE]);
    switch (cp.screen) {
    case CP_HOME:
        home();
        break;
    case CP_PHONE:
        dialer();
        break;
    case CP_CONTACTS:
        if (cp.users < CP_USERS)
            key(196, 22, 36, 20, "+", ACT(K_CMD, C_ADD), ACCENT);
        else
            right(228, 28, "FULL", muted, 1);
        list();
        break;
    case CP_CALLLOG:
    case CP_MESSAGES:
        list();
        break;
    case CP_TRACKLIST:
        title("Tracks");
        list();
        break;
    case CP_CONTACT:
        contact();
        break;
    case CP_CHAT:
        chat();
        break;
    case CP_CALC:
        calc();
        break;
    case CP_MUSIC:
        music();
        break;
    case CP_PHOTOS:
        photos_grid();
        break;
    case CP_PHOTO:
        photo_view();
        break;
    case CP_CAMERA:
        camera();
        break;
    case CP_SETTINGS:
        settings();
        break;
    case CP_SETPAGE:
        setpage();
        break;
    case CP_ADDCONTACT:
        form();
        break;
    case CP_CALL:
        call();
        break;
    default:
        game();
        break;
    }
    navbar();
    if (cp.focus < button_count) {
        button *b = &buttons[cp.focus];
        fill(b->x + 4, focus_y(b), b->w - 8, 1, ACCENT);
    }
}
/* Layout replay: register hit boxes with an empty clip, drawing nothing.
 * Runs once anything was invalidated since the last replay, before the
 * render or the input that looks the boxes up, whichever comes first. */
static void relayout(void)
{
    stale = 0;
    layout = 1;
    button_count = hit_seq = 0;
    prim = first = 0;
    clip[0] = clip[1] = clip[2] = clip[3] = 0;
    paint();
    layout = 0;
    if (cp.focus != 255 && cp.focus >= button_count)
        cp.focus = 255;
    // A layout change (scrolling, a new keyboard layer) under a held button
    // cancels the press, and its highlight is repainted away.
    if (cp.press_action) {
        button *b = &buttons[cp.press_index];
        if (cp.press_index >= button_count || b->action != cp.press_action ||
            b->x != cp.press_box[0] || b->y != cp.press_box[1] ||
            b->w != cp.press_box[2] || b->h != cp.press_box[3]) {
            dirty(cp.press_box[0], cp.press_box[1], cp.press_box[2],
                  cp.press_box[3]);
            cp.press_action = 0;
        }
    }
}
int cp_render(void)
{
    if (!ndamage)
        return 0;
    fg = cp.theme ? RGB(235, 244, 252) : INK;
    bg = cp.theme ? RGB(15, 24, 40) : RGB(219, 232, 226);
    panel = cp.theme ? RGB(35, 49, 68) : RGB(195, 216, 205);
    muted = cp.theme ? RGB(146, 170, 192) : RGB(75, 103, 100);
    bar = cp.theme ? INK : RGB(165, 193, 183);
    if (stale)
        relayout();
    for (unsigned i = 0; i < ndamage; i++) {
        for (int k = 0; k < 4; k++)
            area[k] = clip[k] = damage[i][k];
        first = 0;
        // Probing for an occluding fill pays off for small edits (a text
        // field, a sprite); large areas such as wipe strips skip the extra
        // pass.
        int size = (area[2] - area[0]) * (area[3] - area[1]);
        if (size > 256 && size <= 4096) {
            probing = 1;
            prim = hit_seq = 0;
            paint();
            probing = 0;
        }
        prim = hit_seq = 0;
        paint();
    }
    ndamage = 0;
    cp_flush();
    cp.frames++;
    return 1;
}

/* ------------------------------------------------------------ navigation */
/* A wipe reveals horizontal bands, downwards when going forward or locking
 * and upwards when going back or unlocking. Bands rather than columns, so
 * each step replays only the few list rows or icons inside its band. */
static void trans_band(unsigned from, unsigned count)
{
    int whole = cp.trans_dir < D_UNLOCK, top = whole ? 18 : 0;
    int bottom = whole ? NAVY : CP_H, h = (bottom - top + STRIPS - 1) / STRIPS;
    int a = (int) from * h, b = (int) (from + count) * h;
    b = b < bottom - top ? b : bottom - top;
    if (cp.trans_dir == D_PUSH || cp.trans_dir == D_LOCK)
        dirty(0, top + a, CP_W, b - a);
    else
        dirty(0, bottom - b, CP_W, b - a);
}
static void photo_box(unsigned i)
{
    dirty(12 + (int) (i % 2) * 120, 46 + (int) (i / 2) * 82, 96, 64);
}
static void trans_step(void)
{
    if (cp.trans_dir == D_PHOTOS)
        photo_box(6u - cp.trans); /* the next photo of the grid */
    else
        trans_band(STRIPS - cp.trans, 1);
    cp.trans--;
}
/* Reveal the rest at once: one rectangle, so one replay of the scene. */
static void trans_finish(void)
{
    if (cp.trans && cp.trans_dir != D_PHOTOS)
        trans_band(STRIPS - cp.trans, cp.trans);
    if (cp.trans_dir != D_PHOTOS)
        cp.trans = 0;
}
static void game_init(unsigned screen);
static void enter(unsigned screen, unsigned selected, int scroll, unsigned dir)
{
    // Games share `g`: returning to one (Back, unlock) whose state another
    // game has since overwritten starts it afresh.
    if (screen >= CP_SNAKE && screen <= CP_TETRIS && cp.game != screen)
        game_init(screen);
    cp.screen = (uint8_t) screen;
    cp.selected = (uint8_t) (screen == CP_MUSIC ? cp.track : selected);
    cp.scroll = (int16_t) scroll;
    cp.focus = 255;
    cp.pressed = cp.dragging = cp.grab = 0;
    cp.press_action = 0;
    cp.trans = STRIPS;
    cp.trans_dir = (uint8_t) dir;
    cp.rows_due = 0;
    stale = 1; /* a new scene; its wipe only repaints */
    if (dir < D_UNLOCK) {
        inval(0, 0, CP_W, 18);
        inval(0, NAVY, CP_W, CP_H - NAVY);
    }
    // A band edge through a photo costs one fill per source pixel, so photo
    // screens skip the wipe. The grid then decodes one photo per tick (input
    // does not hurry it), keeping every event within its time budget.
    if (screen == CP_PHOTOS || screen == CP_PHOTO) {
        inval(0, 0, CP_W, CP_H);
        cp.trans = screen == CP_PHOTOS ? 6 : 0;
        cp.trans_dir = D_PHOTOS;
    } else
        trans_step();
}
static void save(void)
{
    if (cp.depth == CP_NAV_DEPTH) {
        for (unsigned i = 1; i < CP_NAV_DEPTH; i++)
            cp.stack[i - 1] = cp.stack[i];
        cp.depth--;
    }
    cp.stack[cp.depth].screen = cp.screen;
    cp.stack[cp.depth].selected = cp.selected;
    cp.stack[cp.depth].scroll = cp.scroll;
    cp.depth++;
}
static void game_init(unsigned screen)
{
    uint8_t *g = (uint8_t *) &cp.g;
    for (unsigned i = 0; i < sizeof cp.g; i++)
        g[i] = 0;
    cp.game = (uint8_t) screen;
    cp.running = 1;
    cp.over = 0;
    cp.score = 0;
    cp.game_ms = 0;
    if (screen == CP_SNAKE) {
        cp.g.snake.len = 4;
        for (int i = 0; i < 4; i++)
            cp.g.snake.body[i] = (uint16_t) (9 - i + 8 * 22);
    }
    if (screen == CP_PONG) {
        cp.g.pong.x = 100;
        cp.g.pong.y = 120;
        cp.g.pong.vx = 3;
        cp.g.pong.vy = 2;
        cp.g.pong.paddle = 104;
    }
    if (screen == CP_TETRIS)
        cp.g.tetris.x = 3;
}
static void prepare(unsigned screen, unsigned selected)
{
    if (screen >= CP_SNAKE && screen <= CP_TETRIS)
        game_init(screen);
    if (screen == CP_CALL)
        cp.call_secs = cp.call_ms = 0;
    if (screen == CP_ADDCONTACT) {
        for (unsigned f = 0; f < 3; f++)
            form_field(f)[0] = 0;
        cp.field = cp.form_error = 0;
        cp.kbd = 1;
    }
    if (screen == CP_CHAT) {
        cp.kbd = 1;
        if (cp.draft_owner != selected) {
            cp.draft[0] = 0;
            cp.draft_owner = (uint8_t) selected;
        }
    }
}
static void open(unsigned screen, unsigned selected)
{
    save();
    prepare(screen, selected);
    enter(screen, selected, 0, D_PUSH);
}
static void go_root(unsigned screen)
{
    if (cp.screen == screen && cp.depth <= 1 && !cp.selected)
        return;
    cp.depth = 0;
    if (screen != CP_HOME) {
        cp.stack[0] = (cp_nav) {CP_HOME, 0, 0};
        cp.depth = 1;
        prepare(screen, 0);
    }
    enter(screen, 0, 0, screen == CP_HOME ? D_POP : D_PUSH);
}
static void release(void);
static void set_page(unsigned page)
{
    if (cp.screen != CP_HOME || cp.selected == page)
        return;
    release();
    cp.selected = (uint8_t) page;
    cp.focus = 255;
    cp.trans = STRIPS;
    cp.trans_dir = page ? D_PUSH : D_POP;
    stale = 1; /* the other page's icons; the wipe only repaints */
    trans_step();
}
static void back(void)
{
    if (cp.screen == CP_HOME) {
        set_page(0);
        return;
    }
    if (!cp.depth) {
        go_root(CP_HOME);
        return;
    }
    cp_nav n = cp.stack[--cp.depth];
    enter(n.screen, n.selected, n.scroll, D_POP);
}
static void lock(void)
{
    if (cp.screen == CP_LOCK)
        return;
    if (cp.screen >= CP_SNAKE && cp.screen <= CP_TETRIS)
        cp.running = 0;
    save();
    cp.slide = 0;
    enter(CP_LOCK, 0, 0, D_LOCK);
}
static void unlock(void)
{
    cp.slide = 0;
    if (cp.depth) {
        cp_nav n = cp.stack[--cp.depth];
        enter(n.screen, n.selected, n.scroll, D_UNLOCK);
    } else
        enter(CP_HOME, 0, 0, D_UNLOCK);
}

/* ------------------------------------------------------------ app logic */
static void calc_format(int64_t v)
{
    char *s = cp.calc;
    uint64_t a = v < 0 ? 0u - (uint64_t) v : (uint64_t) v;
    uint32_t whole = (uint32_t) (a / SCALE), frac = (uint32_t) (a % SCALE);
    if (v < 0)
        *s++ = '-';
    s = number(s, (int32_t) whole);
    if (frac) {
        *s++ = '.';
        for (uint32_t d = SCALE / 10; d && frac; d /= 10) {
            *s++ = (char) ('0' + frac / d);
            frac %= d;
        }
    }
    *s = 0;
}
static int64_t calc_parse(void)
{
    const char *s = cp.calc;
    int neg = *s == '-';
    int64_t v = 0;
    int32_t unit = 0; /* 0 while in the integer part */
    for (s += neg; *s; s++)
        if (*s == '.')
            unit = SCALE;
        else if (!unit)
            v = v * 10 + (*s - '0') * SCALE;
        else {
            unit /= 10;
            v += (*s - '0') * unit;
        }
    return neg ? -v : v;
}
static int calc_apply(void)
{
    int64_t a = cp.acc, b = cp.operand, r = b;
    if (cp.op == '+')
        r = a + b;
    else if (cp.op == '-')
        r = a - b;
    else if (cp.op == '*')
        r = a / SCALE * b + a % SCALE * b / SCALE;
    else if (cp.op == '/') {
        if (!b)
            return 0;
        r = a * SCALE / b;
    }
    if (r > LIMIT || r < -LIMIT)
        return 0;
    cp.acc = r;
    return 1;
}
static void calc_key(unsigned c)
{
    unsigned n = length(cp.calc);
    int digit = c >= '0' && c <= '9';
    if (c == 'C' || c == 'c') {
        cp.acc = cp.operand = 0;
        cp.op = cp.error = 0;
        cp.fresh = 1;
        copy(cp.calc, "0", 2);
    } else if (digit || c == '.') {
        if (cp.fresh || cp.error) {
            copy(cp.calc, "0", 2);
            n = 1;
            cp.fresh = cp.error = 0;
        }
        unsigned dot = 0, frac = 0, whole = 0;
        for (unsigned i = 0; i < n; i++)
            if (cp.calc[i] == '.')
                dot = 1;
            else if (cp.calc[i] != '-')
                dot ? frac++ : whole++;
        if (c == '.') {
            if (dot)
                return;
        } else if (frac >= 3 || (!dot && whole >= 7))
            return;
        if (digit && !dot && cp.calc[n - 1] == '0' && whole == 1)
            n--; /* replace a lone leading zero */
        cp.calc[n] = (char) c;
        cp.calc[n + 1] = 0;
        cp.operand = calc_parse();
    } else if (cp.error) {
        return;
    } else if (c == 8) {
        if (cp.fresh)
            return;
        cp.calc[n - 1] = 0;
        if (n == 1 || (n == 2 && cp.calc[0] == '-'))
            copy(cp.calc, "0", 2);
        cp.operand = calc_parse();
    } else if (c == 'n') {
        cp.operand = -cp.operand;
        calc_format(cp.operand);
    } else if (c == '%') {
        cp.operand /= 100;
        calc_format(cp.operand);
        cp.fresh = 1;
    } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '=') {
        if (cp.op && (!cp.fresh || c == '=')) {
            // Apply the pending operation before a new operator or '='.
            if (!calc_apply()) {
                cp.error = 1;
                cp.op = 0;
                cp.fresh = 1;
                inval_calc();
                return;
            }
            cp.operand = cp.acc;
            calc_format(cp.acc);
        } else if (!cp.op)
            cp.acc = cp.operand;
        // An operator right after another one only replaces it.
        cp.op = c == '=' ? 0 : (char) c;
        cp.fresh = 1;
        cp.error = 0;
    } else
        return;
    cp.value = (int32_t) (cp.operand / SCALE);
    inval_calc();
}
/* Repaint only the span of the centered number, before or after the edit. */
static void inval_dial(unsigned before)
{
    unsigned after = length(cp.dial), m = before > after ? before : after;
    if (!before || !after || m > 17)
        inval(16, 32, 208, 32);
    else
        inval(120 - (int) m * 6 - 1, 40, (int) m * 12 + 2, 16);
}
static void inval_chat_input(void)
{
    inval(12, input_y() + 5, 172, 10);
}
static void inval_status_music(void)
{
    if (cp.screen != CP_MUSIC)
        inval(142, 5, 32, 8);
}
static void inval_music(void)
{
    if (cp.screen == CP_MUSIC)
        inval_content();
    if (cp.screen == CP_HOME && cp.selected)
        inval(12, 108, 216, 60);
    if (cp.screen == CP_TRACKLIST)
        inval(0, TOP, CP_W, BOTTOM - TOP);
    inval_status_music();
}
static void set_track(unsigned t)
{
    cp.track = (uint8_t) (t % N_TRACKS);
    cp.position = cp.music_ms = 0;
    if (cp.screen == CP_MUSIC)
        cp.selected = cp.track;
    inval_music();
}
static void set_position(unsigned pos)
{
    if (pos == cp.position)
        return;
    int w0 = progress();
    cp.position = (uint16_t) pos;
    int w1 = progress();
    if (cp.screen == CP_MUSIC) {
        inval(20, 184, 30, 8);
        if (w0 != w1)
            inval(18 + (w0 < w1 ? w0 : w1), 169,
                  (w0 < w1 ? w1 - w0 : w0 - w1) + 5, 10);
    }
    inval_status_music();
}
static void seek(int x)
{
    int d = durations[cp.track];
    int p = (x - 20) * d / 200;
    p = p < 0 ? 0 : p >= d ? d - 1 : p;
    cp.music_ms = 0;
    set_position((unsigned) p);
}
/* Only the band where bar heights change is repainted, in three groups of
 * six bars: few rectangles keep the number of scene replays low. */
static void eq_inval(unsigned phase, unsigned on)
{
    if (cp.screen != CP_MUSIC)
        return;
    for (unsigned g = 0; g < 18; g += 6) {
        int lo = 292, hi = 0;
        for (unsigned i = g; i < g + 6; i++) {
            int a = eq_height(i, cp.eq, cp.playing),
                b = eq_height(i, phase, on);
            if (a != b) {
                int top = 292 - (a > b ? a : b), bottom = 292 - (a > b ? b : a);
                lo = top < lo ? top : lo;
                hi = bottom > hi ? bottom : hi;
            }
        }
        if (hi)
            inval(17 + (int) g * 12, lo, 69, hi - lo);
    }
}
static void eq_advance(unsigned phase)
{
    eq_inval(phase, cp.playing);
    cp.eq = (uint8_t) phase;
}
static void play(int on)
{
    if (cp.playing == on)
        return;
    if (cp.screen == CP_MUSIC)
        inval(92, 200, 56, 32);
    eq_inval(cp.eq, (unsigned) on); /* bars fall flat when paused */
    cp.playing = (uint8_t) on;
    cp.eq_ms = 0;
    if (cp.screen == CP_HOME && cp.selected)
        inval(12, 108, 216, 60);
    inval(142, 5, 32, 8);
}
static void dial_append(unsigned c)
{
    unsigned n = length(cp.dial);
    if (n + 1 >= sizeof cp.dial)
        return;
    cp.dial[n] = (char) c;
    cp.dial[n + 1] = 0;
    inval_dial(n);
}
static void draft_delete(char *s)
{
    unsigned n = length(s);
    if (!n)
        return;
    s[n - 1] = 0;
    if (s == cp.dial)
        inval_dial(n);
    else
        inval_chat_input();
}
static void send(void)
{
    if (!cp.draft[0])
        return; /* an empty send keeps the history */
    unsigned slot = cp.sent_head;
    cp.sent[slot].who = cp.selected;
    copy(cp.sent[slot].text, cp.draft, CP_MSG);
    cp.sent_head = (uint8_t) ((slot + 1) % CP_SENT);
    if (cp.sent_count < CP_SENT)
        cp.sent_count++;
    cp.draft[0] = 0;
    inval(0, TOP, CP_W, input_y() + 20 - TOP);
}
static void input_char(unsigned c)
{
    if (cp.screen == CP_PHONE) {
        if ((c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+')
            dial_append(c);
    } else if (cp.screen == CP_CALC)
        calc_key(c);
    else if (cp.screen == CP_ADDCONTACT && c >= 32 && c < 127) {
        char *v = form_field(cp.field);
        unsigned n = length(v);
        if (n + 1 < form_size(cp.field)) {
            v[n] = (char) c;
            v[n + 1] = 0;
            inval(16, field_y(cp.field) + 5, 208, 10);
        }
    } else if (cp.screen == CP_CHAT && c >= 32 && c < 127) {
        unsigned n = length(cp.draft);
        if (n + 1 >= CP_MSG)
            return;
        cp.draft[n] = (char) c;
        cp.draft[n + 1] = 0;
        cp.draft_owner = cp.selected;
        inval_chat_input();
    }
}
static void start_call(unsigned who)
{
    cp.call_who = (uint8_t) who;
    open(CP_CALL, cp.selected);
}
static void photo_step(int d)
{
    cp.selected = (uint8_t) ((cp.selected + 6 + d) % 6);
    inval(0, 18, CP_W, 200);
}
// Backspace in the form's focused field.
static void form_delete(void)
{
    char *v = form_field(cp.field);
    unsigned n = length(v);
    if (n) {
        v[n - 1] = 0;
        inval(16, field_y(cp.field) + 5, 208, 10);
    }
}
// Only the form saves, once per slot, even if a batch of input repeats
// the tap. The new id goes into the A-Z order, after equal names.
static void form_save(void)
{
    if (cp.screen != CP_ADDCONTACT || cp.users >= CP_USERS)
        return;
    if (!form_field(0)[0]) {
        cp.form_error = 1;
        inval(12, 190, 216, 8);
        return;
    }
    unsigned id = N_CONTACTS + cp.users, n = contacts_n(), k = 0;
    while (k < n && !name_before(id, cp.order[k]))
        k++;
    for (unsigned j = n; j > k; j--)
        cp.order[j] = cp.order[j - 1];
    cp.order[k] = (uint8_t) id;
    cp.users++;
    back();
}
static void set_kbd(unsigned on)
{
    if (cp.kbd != on) {
        cp.kbd = (uint8_t) on;
        inval(0, TOP, CP_W, BOTTOM - TOP);
    }
}
// Focus a form field; typing into it needs the keyboard.
static void form_focus(unsigned f)
{
    field_inval(cp.field);
    cp.field = (uint8_t) f;
    field_inval(cp.field);
    set_kbd(1);
}
static void command(unsigned c)
{
    int form = cp.screen == CP_ADDCONTACT;
    switch (c) {
    case C_CALL:
        if (cp.screen == CP_CONTACT)
            start_call(cp.selected);
        else if (cp.dial[0])
            start_call(255);
        break;
    case C_DEL:
        if (form)
            form_delete();
        else
            draft_delete(cp.screen == CP_PHONE ? cp.dial : cp.draft);
        break;
    case C_SEND:
        if (form)
            form_focus((cp.field + 1) % 3u);
        else
            send();
        break;
    case C_ADD:
        if (cp.users < CP_USERS)
            open(CP_ADDCONTACT, 0);
        break;
    case C_CANCEL:
    case C_END:
        back();
        break;
    case C_SAVE:
        form_save();
        break;
    case C_MEMORY:
        open(CP_SETPAGE, 5);
        break;
    case C_SHIFT:
    case C_LAYER:
        cp.kbd_layer = (uint8_t) (c == C_SHIFT        ? !cp.kbd_layer
                                  : cp.kbd_layer == 2 ? 0
                                                      : 2);
        inval(0, 204, CP_W, 96);
        break;
    case C_KBD_CLOSE:
    case C_KBD_OPEN:
        set_kbd(c == C_KBD_OPEN);
        break;
    case C_PREV:
    case C_NEXT:
        if (cp.screen == CP_PHOTO)
            photo_step(c == C_PREV ? -1 : 1);
        else
            set_track(cp.track + (c == C_PREV ? N_TRACKS - 1 : 1));
        break;
    case C_PLAY:
        play(!cp.playing);
        break;
    case C_LIST:
        open(CP_TRACKLIST, 0);
        cp.scroll =
            (int16_t) (cp.track * 34 > list_max() ? list_max() : cp.track * 34);
        break;
    case C_CAPTURE:
        cp.shots = (uint8_t) (cp.shots % 99 + 1);
        inval(60, 214, 120, 16);
        break;
    case C_MESSAGE:
        open(CP_CHAT, cp.selected);
        break;
    case C_PAGE0:
    case C_PAGE1:
        set_page(c - C_PAGE0);
        break;
    default:
        if (c >= C_FIELD0 && c < C_FIELD0 + 3)
            form_focus(c - C_FIELD0);
    }
}
static void set_minute(int m)
{
    cp.minute = (uint16_t) ((m + 1440) % 1440);
    cp.clock_ms = 0;
    cp_invalidate();
}
static void setting(unsigned s)
{
    switch (s) {
    case S_BRIGHT_DN:
    case S_BRIGHT_UP: {
        unsigned b = cp.brightness + (s == S_BRIGHT_UP ? 1 : -1);
        if (b < 3 || b > 10)
            return;
        cp.brightness = (uint8_t) b;
        cp_invalidate();
        return;
    }
    case S_VOL_DN:
    case S_VOL_UP: {
        unsigned v = cp.volume + (s == S_VOL_UP ? 1 : -1);
        if (v > 10)
            return;
        cp.volume = (uint8_t) v;
        inval(16, 60, 208, 76);
        return;
    }
    case S_THEME0:
    case S_THEME1:
        if (cp.theme == s - S_THEME0)
            return;
        cp.theme = (uint8_t) (s - S_THEME0);
        cp_invalidate();
        return;
    case S_WALL0:
    case S_WALL1:
    case S_WALL2:
        if (cp.wallpaper == s - S_WALL0)
            return;
        cp.wallpaper = (uint8_t) (s - S_WALL0);
        inval_content();
        return;
    default:
        set_minute(cp.minute + (s == S_HOUR_UP   ? 60
                                : s == S_MIN_UP  ? 1
                                : s == S_HOUR_DN ? -60
                                                 : -1));
    }
}
static void item(unsigned i)
{
    switch (cp.screen) {
    case CP_CONTACTS:
        open(CP_CONTACT, cp.order[i % contacts_n()]);
        break;
    case CP_MESSAGES:
        open(CP_CHAT, i);
        break;
    case CP_CALLLOG:
        if (call_contact[i] != 255)
            open(CP_CONTACT, call_contact[i]);
        else
            start_call(128 + i);
        break;
    case CP_PHOTOS:
        open(CP_PHOTO, i);
        break;
    case CP_TRACKLIST:
        set_track(i);
        play(1);
        back();
        break;
    case CP_SETTINGS:
        if (i < 5)
            open(CP_SETPAGE, i);
        else
            lock();
        break;
    }
}
static void action(unsigned a)
{
    unsigned v = a & 255;
    switch (a >> 8) {
    case K_NAV:
        if (v == 0)
            back();
        else
            go_root(v == 1 ? CP_HOME : v == 2 ? CP_PHONE : CP_CONTACTS);
        break;
    case K_APP:
        open(CP_PHONE + v, 0);
        break;
    case K_ITEM:
        item(v);
        break;
    case K_CHAR:
        input_char(v);
        break;
    case K_CMD:
        command(v);
        break;
    case K_SET:
        setting(v);
        break;
    }
}

/* ----------------------------------------------------------------- games */
static void cell_inval(unsigned p)
{
    inval(22 + (int) (p % 22) * 8, 57 + (int) (p / 22) * 8, 7, 7);
}
static void score_inval(void)
{
    inval(68, 221, 120, 16);
}
static void game_status_inval(void)
{
    inval(144, 265, 60, 8);
}
static void pong_inval(void)
{
    inval(cp.g.pong.x - 3, cp.g.pong.y - 3, 7, 7);
    inval(196, opponent_y(), 4, 30);
}
static void piece_inval(void)
{
    inval(57 + cp.g.tetris.x * 10, 57 + cp.g.tetris.y * 10, 40, 40);
}
static int fits(int x, int y, unsigned rot)
{
    unsigned bits = shapes[cp.g.tetris.piece % 7][rot % 4];
    for (int i = 0; i < 16; i++)
        if (bits & (1u << i)) {
            int xx = x + i % 4, yy = y + i / 4;
            if (xx < 0 || xx >= 10 || yy < 0 || yy >= 14 ||
                cp.g.tetris.board[yy][xx])
                return 0;
        }
    return 1;
}
static void tetris_move(int dx, int dy, unsigned rot)
{
    piece_inval();
    cp.g.tetris.x = (int8_t) (cp.g.tetris.x + dx);
    cp.g.tetris.y = (int8_t) (cp.g.tetris.y + dy);
    cp.g.tetris.rot = (uint8_t) rot;
    piece_inval();
}
static void drop(void)
{
    if (fits(cp.g.tetris.x, cp.g.tetris.y + 1, cp.g.tetris.rot)) {
        tetris_move(0, 1, cp.g.tetris.rot);
        return;
    }
    unsigned bits = shapes[cp.g.tetris.piece % 7][cp.g.tetris.rot % 4];
    for (int i = 0; i < 16; i++)
        if (bits & (1u << i))
            cp.g.tetris.board[cp.g.tetris.y + i / 4][cp.g.tetris.x + i % 4] =
                (uint8_t) (cp.g.tetris.piece % 7 + 1);
    piece_inval();
    for (int y = 13; y >= 0; y--) {
        int full = 1;
        for (int x = 0; x < 10; x++)
            if (!cp.g.tetris.board[y][x])
                full = 0;
        if (full) {
            for (int row = y; row > 0; row--)
                for (int x = 0; x < 10; x++)
                    cp.g.tetris.board[row][x] = cp.g.tetris.board[row - 1][x];
            for (int x = 0; x < 10; x++)
                cp.g.tetris.board[0][x] = 0;
            cp.score += 10;
            score_inval();
            inval(57, 57, 100, 140);
            y++;
        }
    }
    cp.g.tetris.piece = (uint8_t) ((cp.g.tetris.piece + 1) % 7);
    cp.g.tetris.x = 3;
    cp.g.tetris.y = 0;
    cp.g.tetris.rot = 0;
    piece_inval();
    if (!fits(cp.g.tetris.x, cp.g.tetris.y, 0)) {
        cp.over = 1;
        game_status_inval();
    }
}
static void snake_step(void)
{
    uint16_t *body = cp.g.snake.body;
    unsigned len = cp.g.snake.len, dir = cp.g.snake.dir;
    if (!len || len > 64)
        cp.g.snake.len = (uint8_t) (len = len ? 64 : 1);
    int x = body[0] % 22 + (dir == 0) - (dir == 2);
    int y = body[0] / 22 + (dir == 1) - (dir == 3);
    cp.g.snake.last = (uint8_t) dir;
    if (x < 0 || x >= 22 || y < 0 || y >= 18) {
        cp.over = 1;
        game_status_inval();
        return;
    }
    unsigned next = (unsigned) (x + y * 22);
    int eat = x == food_x() && y == food_y(), grow = eat && len < 64;
    // The tail only moves out of the way when the snake does not grow; at
    // full length (64) eating scores but the tail still moves.
    for (unsigned i = 0; i + (grow ? 0 : 1) < len; i++)
        if (body[i] == next) {
            cp.over = 1;
            game_status_inval();
            return;
        }
    if (eat) {
        cp.score++;
        score_inval();
        cell_inval((unsigned) (food_x() + food_y() * 22));
    }
    if (grow)
        cp.g.snake.len = (uint8_t) ++len;
    else
        cell_inval(body[len - 1]);
    cell_inval(body[0]);
    for (unsigned i = len - 1; i > 0; i--)
        body[i] = body[i - 1];
    body[0] = (uint16_t) next;
    cell_inval(next);
}
/* The caller invalidates the ball before and after a run of steps; the
 * positions in between are never shown. */
static void pong_step(void)
{
    cp.g.pong.x = (int16_t) (cp.g.pong.x + cp.g.pong.vx);
    cp.g.pong.y = (int16_t) (cp.g.pong.y + cp.g.pong.vy);
    if (cp.g.pong.y < 60 || cp.g.pong.y > 201)
        cp.g.pong.vy = (int16_t) -cp.g.pong.vy;
    if (cp.g.pong.x >= 190) {
        cp.g.pong.vx = -3;
        cp.score++;
        score_inval();
    }
    if (cp.g.pong.x <= 32) {
        int p = cp.g.pong.paddle;
        if (cp.g.pong.y >= p - 3 && cp.g.pong.y <= p + 33) {
            cp.g.pong.vx = 3;
            cp.score++;
            score_inval();
        } else {
            cp.over = 1;
            game_status_inval();
        }
    }
}
static void set_paddle(int y)
{
    if (y == cp.g.pong.paddle)
        return;
    inval(24, cp.g.pong.paddle, 4, 30);
    cp.g.pong.paddle = (int16_t) y;
    inval(24, y, 4, 30);
}
static int in_game(void)
{
    return cp.screen >= CP_SNAKE && cp.screen <= CP_TETRIS;
}
static void game_key(unsigned k)
{
    if (k == ' ') {
        if (!cp.over) {
            cp.running = !cp.running;
            game_status_inval();
        }
        return;
    }
    if (!cp.running || cp.over)
        return;
    if (cp.screen == CP_SNAKE) {
        unsigned d = k == CP_RIGHT      ? 0
                     : k == CP_DOWN_KEY ? 1
                     : k == CP_LEFT     ? 2
                                        : 3;
        // Compare against the last step actually taken, so two quick turns
        // within one tick cannot reverse the snake into itself.
        if (k >= CP_LEFT && k <= CP_DOWN_KEY && (d + 2) % 4 != cp.g.snake.last)
            cp.g.snake.dir = (uint8_t) d;
    } else if (cp.screen == CP_PONG) {
        if (k == CP_UP_KEY && cp.g.pong.paddle > 61)
            set_paddle(cp.g.pong.paddle - 8);
        if (k == CP_DOWN_KEY && cp.g.pong.paddle < 170)
            set_paddle(cp.g.pong.paddle + 8);
    } else {
        int dx = k == CP_LEFT ? -1 : k == CP_RIGHT ? 1 : 0;
        if (dx && fits(cp.g.tetris.x + dx, cp.g.tetris.y, cp.g.tetris.rot))
            tetris_move(dx, 0, cp.g.tetris.rot);
        if (k == CP_UP_KEY &&
            fits(cp.g.tetris.x, cp.g.tetris.y, (cp.g.tetris.rot + 1) % 4u))
            tetris_move(0, 0, (cp.g.tetris.rot + 1) % 4u);
        if (k == CP_DOWN_KEY)
            drop();
    }
}

static void rows_step(void);
/* ----------------------------------------------------------------- time */
static void minute_passed(void)
{
    if (++cp.minute == 1440) {
        cp.minute = 0;
        cp.wday = (uint8_t) ((cp.wday + 1) % 7);
        if (++cp.mday > month_days[cp.month]) {
            cp.mday = 1;
            cp.month = (uint8_t) ((cp.month + 1) % 12);
        }
    }
    if (cp.minute % 10 == 0 && cp.battery > 5) {
        cp.battery--;
        inval(178, 4, 52, 10);
    }
    inval_clock();
    if (cp.screen == CP_SETTINGS || cp.screen == CP_SETPAGE)
        inval_content();
}
static void tick(unsigned ms)
{
    if (ms > 1000)
        ms = 1000;
    if (cp.trans)
        trans_step();
    rows_step();
    if (!ms)
        return;
    cp.clock_ms = (uint16_t) (cp.clock_ms + ms);
    if (cp.clock_ms >= 60000) {
        cp.clock_ms = (uint16_t) (cp.clock_ms - 60000);
        minute_passed();
    }
    if (cp.playing) {
        unsigned e = cp.music_ms + ms, pos = cp.position + e / 1000;
        cp.music_ms = (uint16_t) (e % 1000);
        cp.eq_ms = (uint16_t) (cp.eq_ms + ms);
        if (cp.eq_ms >= 66) {
            eq_advance((cp.eq + cp.eq_ms / 66u) & 255);
            cp.eq_ms %= 66;
        }
        if (pos >= durations[cp.track]) {
            unsigned ms_left = cp.music_ms;
            set_track(cp.track + 1u); /* auto-advance */
            cp.music_ms = (uint16_t) ms_left;
        } else
            set_position(pos);
    }
    if (cp.screen == CP_CALL) {
        cp.call_ms = (uint16_t) (cp.call_ms + ms);
        if (cp.call_ms >= 1000) {
            cp.call_ms = (uint16_t) (cp.call_ms - 1000);
            cp.call_secs++;
            inval(0, 164, CP_W, 8);
        }
    }
    int game_on = in_game() && cp.running && !cp.over;
    if (game_on) {
        unsigned period = cp.screen == CP_PONG    ? 33
                          : cp.screen == CP_SNAKE ? 100
                                                  : 300;
        cp.game_ms = (uint16_t) (cp.game_ms + ms);
        int pong = cp.screen == CP_PONG && cp.game_ms >= period;
        if (pong)
            pong_inval();
        while (cp.game_ms >= period && !cp.over) {
            cp.game_ms = (uint16_t) (cp.game_ms - period);
            if (cp.screen == CP_PONG)
                pong_step();
            else if (cp.screen == CP_SNAKE)
                snake_step();
            else
                drop();
        }
        if (pong)
            pong_inval();
        // Time after a fatal step is inactive time, like time spent paused.
        if (cp.over)
            cp.game_ms = 0;
    }
    if (cp.screen == CP_LOCK || cp.screen == CP_CALL || game_on)
        cp.idle_ms = 0;
    else if ((cp.idle_ms += ms) >= IDLE_LOCK_MS)
        lock();
}

/* ---------------------------------------------------------------- input */
/* A list drag repaints one row slot per event (move or tick) instead of the
 * whole viewport, which would not fit one partial update; release repaints
 * whatever is still due. Rows keep their places because scrolling is by
 * whole rows. */
static void rows_step(void)
{
    if (!cp.rows_due)
        return;
    int k = 0, h = list_h();
    while (!(cp.rows_due & (1u << k)))
        k++;
    cp.rows_due &= (uint8_t) ~(1u << k);
    int y = TOP + k * h;
    // The scroll bar column is a separate rectangle: overlapping, they would
    // merge into the whole viewport. The scroll itself was an inval().
    dirty(0, y, 236, (y + h < BOTTOM ? y + h : BOTTOM) - y);
}
static void rows_finish(void)
{
    if (!cp.rows_due)
        return;
    int k = 0;
    while (!(cp.rows_due & (1u << k)))
        k++;
    dirty(0, TOP + k * list_h(), 236, BOTTOM - TOP - k * list_h());
    cp.rows_due = 0;
}
static void scroll_to(int s, int dragging)
{
    int max = list_max(), h = list_h();
    s = (s + h / 2) / h * h;
    s = s < 0 ? 0 : s > max ? max : s;
    if (s == cp.scroll)
        return;
    cp.scroll = (int16_t) s;
    if (dragging) {
        cp.rows_due = (uint8_t) ((1u << ((BOTTOM - TOP + h - 1) / h)) - 1);
        inval(236, TOP, 4, BOTTOM - TOP); /* scroll bar column */
    } else {
        cp.rows_due = 0;
        inval(0, TOP, CP_W, BOTTOM - TOP);
    }
}
static void focus(void)
{
    if (!button_count)
        return;
    if (cp.focus < button_count) {
        button *b = &buttons[cp.focus];
        dirty(b->x, focus_y(b), b->w, 1);
    }
    cp.focus =
        (uint8_t) (cp.focus >= button_count ? 0
                                            : (cp.focus + 1) % button_count);
    button *b = &buttons[cp.focus];
    dirty(b->x, focus_y(b), b->w, 1);
}
static void level_set(int x)
{
    unsigned max = 10, v = (unsigned) ((x - 16) * 10 + 104) / 208;
    if (x < 16)
        v = 0;
    if (v > max)
        v = max;
    if (cp.selected == 0) {
        // Brightness repaints everything, so a drag previews the level on the
        // slider and commits it on release.
        if (v < 3)
            v = 3;
        if (v != (unsigned) cp.press_value) {
            cp.press_value = (int16_t) v;
            inval(16, 60, 208, 40);
        }
    } else if (v != cp.volume) {
        cp.volume = (uint8_t) v;
        inval(16, 60, 208, 76);
    }
}
static int is_enter(unsigned k)
{
    return k == CP_ENTER || k == '\r' || k == '\n';
}
static int is_side(unsigned k)
{
    return k == CP_LEFT || k == CP_RIGHT;
}
// Text screens: Enter runs cmd, Backspace deletes, the rest is typed.
static void text_key(unsigned k, int enter, unsigned cmd)
{
    if (enter)
        command(cmd);
    else if (k == 8 || k == 127)
        command(C_DEL);
    else
        input_char(k);
}
// Keys every unlocked screen shares; 1 when k was taken.
static int common_key(unsigned k)
{
    if (k == CP_GO_HOME)
        go_root(CP_HOME);
    else if (k == CP_BACK)
        back();
    else if (k == CP_TAB)
        focus();
    else if (k == CP_ENTER && cp.focus < button_count)
        action(buttons[cp.focus].action);
    else if (in_game())
        game_key(k);
    else if (is_list() && (k == CP_UP_KEY || k == CP_DOWN_KEY))
        scroll_to(cp.scroll + (k == CP_DOWN_KEY ? list_h() : -list_h()), 0);
    else
        return 0;
    return 1;
}
// Keys particular to the screen shown.
static void screen_key(unsigned k)
{
    switch (cp.screen) {
    case CP_HOME:
        if (k >= '1' && k <= '9')
            open(CP_PHONE + k - '1', 0);
        else if (is_side(k))
            set_page(k == CP_RIGHT);
        break;
    case CP_PHONE:
        text_key(k, k == CP_ENTER, C_CALL);
        break;
    case CP_ADDCONTACT:
    case CP_CHAT: /* C_SEND sends outside the form */
        text_key(k, is_enter(k), C_SEND);
        break;
    case CP_CALC:
        calc_key(k == CP_ENTER ? '=' : k == 127 ? 8 : k == 'x' ? '*' : k);
        break;
    case CP_MUSIC:
        if (k == ' ')
            command(C_PLAY);
        else if (is_side(k))
            command(k == CP_LEFT ? C_PREV : C_NEXT);
        break;
    case CP_PHOTO:
        if (is_side(k))
            photo_step(k == CP_LEFT ? -1 : 1);
        break;
    case CP_CAMERA:
        if (k == ' ' || k == CP_ENTER)
            command(C_CAPTURE);
        break;
    case CP_CALL:
        if (k == CP_ENTER)
            back();
        break;
    case CP_SETPAGE:
        if (is_side(k) && cp.selected < 3)
            setting(cp.selected == 2
                        ? S_WALL0 + (cp.wallpaper + (k == CP_RIGHT ? 1 : 2)) % 3
                        : (cp.selected ? S_VOL_DN : S_BRIGHT_DN) +
                              (k == CP_RIGHT));
        break;
    }
}
static void key_event(unsigned k)
{
    if (cp.screen == CP_LOCK) {
        if (k == CP_ENTER)
            unlock();
    } else if (!common_key(k))
        screen_key(k);
}
static int find(int x, int y, int *index)
{
    for (int i = button_count; i > 0; i--) {
        button *b = &buttons[i - 1];
        if (x >= b->x && x < b->x + b->w && y >= b->y && y < b->y + b->h) {
            *index = i - 1;
            return b->action;
        }
    }
    return 0;
}
static void unpress(void)
{
    if (cp.press_action)
        dirty(cp.press_box[0], cp.press_box[1], cp.press_box[2],
              cp.press_box[3]);
}
static void release(void)
{
    if (cp.grab == G_LEVEL && cp.selected == 0 &&
        cp.press_value != cp.brightness) {
        cp.brightness = (uint8_t) cp.press_value;
        cp_invalidate();
    }
    if (cp.grab == G_KNOB && cp.slide) {
        cp.slide = 0;
        inval(14, 256, 212, 28);
    } else if (cp.grab == G_KNOB)
        inval(14, 256, 36, 28);
    if (!cp.dragging)
        unpress();
    cp.pressed = cp.dragging = cp.grab = 0;
    cp.press_action = 0;
}
// A press on the lock knob, the seek bar or a level slider grabs it;
// 1 when the press is spoken for (the lock screen has nothing else).
static int grab_widget(int x, int y)
{
    if (cp.screen == CP_LOCK) {
        if (x >= 14 + cp.slide && x < 50 + cp.slide && y >= 256 && y < 284) {
            cp.grab = G_KNOB;
            cp.press_value = (int16_t) cp.slide;
            inval(14 + cp.slide, 256, 36, 28);
        }
        return 1;
    }
    if (cp.screen == CP_MUSIC && x >= 14 && x < 226 && y >= 164 && y < 188) {
        cp.grab = G_SEEK;
        seek(x);
        return 1;
    }
    if (cp.screen == CP_SETPAGE && cp.selected < 2 && x >= 10 && x < 230 &&
        y >= 56 && y < 80) {
        cp.grab = G_LEVEL;
        cp.press_value = cp.brightness;
        level_set(x);
        return 1;
    }
    return 0;
}
static void press_down(int x, int y)
{
    int i;
    release();
    cp.pressed = 1;
    cp.press_x = (int16_t) x;
    cp.press_y = (int16_t) y;
    if (grab_widget(x, y))
        return;
    if (is_list() && y >= TOP && y < BOTTOM) {
        cp.grab = G_LIST;
        cp.press_value = cp.scroll;
    }
    cp.press_action = (uint16_t) find(x, y, &i);
    if (cp.press_action) {
        button *b = &buttons[i];
        cp.press_index = (uint8_t) i;
        cp.press_box[0] = b->x;
        cp.press_box[1] = (int16_t) b->y;
        cp.press_box[2] = b->w;
        cp.press_box[3] = b->h;
        unpress();
    }
}
static void drag_knob(int dx)
{
    int s = cp.press_value + dx;
    s = s < 0 ? 0 : s > 176 ? 176 : s;
    if (s == cp.slide)
        return;
    // The uncovered strip and the knob at its new place.
    if (s > cp.slide)
        inval(14 + cp.slide, 256, s - cp.slide, 28);
    else
        inval(50 + s, 256, cp.slide - s, 28);
    inval(14 + s, 256, 36, 28);
    cp.slide = (uint16_t) s;
}
static void press_move(int x, int dx, int dy)
{
    if (!cp.dragging && (dx > 8 || dx < -8 || dy > 8 || dy < -8)) {
        unpress();
        cp.dragging = 1;
    }
    switch (cp.grab) {
    case G_KNOB:
        drag_knob(dx);
        break;
    case G_SEEK:
        seek(x);
        break;
    case G_LEVEL:
        level_set(x);
        break;
    case G_LIST:
        if (cp.dragging)
            scroll_to(cp.press_value - dy, 1);
        break;
    }
    rows_step();
}
static void press_up(int x, int y, int dx, int dy)
{
    if (cp.grab == G_KNOB) {
        int unlocked = cp.slide >= 150;
        release();
        if (unlocked)
            unlock();
        return;
    }
    unsigned act = cp.press_action;
    int adx = dx < 0 ? -dx : dx, ady = dy < 0 ? -dy : dy;
    int dragging = cp.dragging, swipe = adx >= 40 && adx > ady;
    int i, same = find(x, y, &i) == (int) act;
    release();
    if (dragging) {
        if (swipe && cp.screen == CP_HOME)
            set_page(dx < 0);
        else if (swipe && cp.screen == CP_PHOTO)
            photo_step(dx < 0 ? 1 : -1);
        return;
    }
    if (act && same)
        action(act);
}
static void pointer(unsigned type, int x, int y)
{
    if (cp.screen == CP_PONG && cp.running && !cp.over && y > 55 && y < 204)
        set_paddle(y < 72 ? 57 : y > 185 ? 170 : y - 15);
    if (type == CP_DOWN)
        press_down(x, y);
    else if (cp.pressed) {
        int dx = x - cp.press_x, dy = y - cp.press_y;
        if (type == CP_MOVE)
            press_move(x, dx, dy);
        else /* CP_UP */
            press_up(x, y, dx, dy);
    }
}
void cp_init(void)
{
    cp = (cp_state) {0};
    cp.focus = 255;
    cp.volume = 6;
    cp.brightness = 10;
    cp.fresh = 1;
    copy(cp.calc, "0", 2);
    cp.minute = 12 * 60 + 3;
    cp.wday = 5;
    cp.mday = 17;
    cp.month = 9;
    cp.battery = 80;
    cp.kbd = 1;
    cp.draft_owner = 255;
    for (unsigned i = 0; i < N_CONTACTS; i++)
        cp.order[i] = (uint8_t) i;
    button_count = 0;
    cp_invalidate();
}
void cp_event(unsigned type, unsigned x, unsigned y, unsigned value)
{
    if (type == CP_TICK) {
        tick(value);
        return;
    }
    cp.idle_ms = 0;
    // Input lands on a fully drawn screen; pointer motion keeps the wipe.
    if (cp.trans && type != CP_MOVE)
        trans_finish();
    if (type != CP_MOVE)
        rows_finish();
    // Platforms deliver every queued event before the next render. Once one
    // has changed the screen (a tap, a key, a scroll, a tick), the hit boxes
    // are those of a screen no longer shown: lay the current one out again
    // before a press, release, Tab or Enter consults them. A press highlight
    // or focus mark moves no box, so a plain tap's release needs no layout.
    if (stale && (type == CP_DOWN || type == CP_UP ||
                  (type == CP_KEY && (value == CP_TAB || value == CP_ENTER))))
        relayout();
    if (type == CP_KEY) {
        key_event(value);
        return;
    }
    if (type < CP_DOWN || type > CP_UP)
        return;
    if (x >= CP_W || y >= CP_H) {
        if (type != CP_MOVE)
            release();
        return;
    }
    pointer(type, (int) x, (int) y);
}
