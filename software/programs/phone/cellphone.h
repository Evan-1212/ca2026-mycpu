// SPDX-License-Identifier: MIT
/* The phone application: screens, input events and drawing. */
#ifndef CELLPHONE_H
#define CELLPHONE_H
#include <stdint.h>
#define CP_W 240
#define CP_H 320
/* Screen numbers are part of the replay/telemetry contract: append only. */
enum {
    CP_LOCK,
    CP_HOME,
    CP_PHONE,
    CP_CONTACTS,
    CP_MESSAGES,
    CP_CALC,
    CP_MUSIC,
    CP_PHOTOS,
    CP_CAMERA,
    CP_SETTINGS,
    CP_CALLLOG,
    CP_SNAKE,
    CP_PONG,
    CP_TETRIS,
    CP_CONTACT,
    CP_CHAT,
    CP_PHOTO,
    CP_CALL,
    CP_TRACKLIST,
    CP_SETPAGE,
    CP_ADDCONTACT
};
/* Event types. CP_KEY values are ASCII or the CP_* key codes below; they are
 * never interpreted as on-screen button actions. */
enum { CP_DOWN = 1, CP_MOVE, CP_UP, CP_KEY, CP_TICK };
enum {
    CP_BACK = 256,
    CP_GO_HOME,
    CP_TAB,
    CP_LEFT,
    CP_RIGHT,
    CP_UP_KEY,
    CP_DOWN_KEY,
    CP_ENTER
};
#define CP_NAV_DEPTH 6 /* bounded navigation history */
#define CP_MSG 40      /* message length including terminator */
#define CP_SENT 4      /* sent messages kept, shared by all conversations */
#define CP_USERS 3     /* contacts added on the phone, beyond the 12 stock */
typedef struct {
    uint8_t screen, selected;
    int16_t scroll;
} cp_nav;
typedef struct {
    /* Navigation: `selected` is the per-screen item (contact, photo, page...),
     * `scroll` the list offset in pixels; both are saved on the stack. */
    uint8_t screen, selected, depth, focus;
    int16_t scroll;
    cp_nav stack[CP_NAV_DEPTH];
    uint8_t trans, trans_dir; /* screen wipe: strips still to reveal */
    uint8_t rows_due;         /* list row slots still to repaint after a drag */
    /* Pointer gesture in progress. */
    uint8_t pressed, dragging, grab, press_index;
    uint16_t press_action;
    int16_t press_x, press_y, press_value;
    int16_t press_box[4];
    uint16_t slide;
    /* Settings. */
    uint8_t theme, wallpaper, brightness, volume;
    /* Wall clock driven by CP_TICK; idle time drives the auto-lock. */
    uint16_t minute, clock_ms;
    uint8_t wday, mday, month, battery;
    uint32_t idle_ms;
    /* Music plays in the background; `position` is in seconds. */
    uint8_t playing, track, eq;
    uint16_t position, music_ms, eq_ms;
    /* Phone. */
    char dial[20];
    uint8_t call_who; /* contact, 128 + call log entry, or 255 = dialed */
    uint16_t call_secs, call_ms;
    /* Calculator: fixed point, three decimals; `value` is the integer part. */
    int32_t value;
    int64_t acc, operand;
    char op, fresh, error;
    char calc[16];
    /* Messages: one draft owned by a conversation, and a small sent ring. */
    uint8_t kbd, kbd_layer, draft_owner, sent_head, sent_count;
    char draft[CP_MSG];
    struct {
        uint8_t who;
        char text[CP_MSG];
    } sent[CP_SENT];
    /* Added contacts (ids 12..14) and the A-Z order of all contact ids. */
    struct {
        char name[16], phone[16], email[24];
    } user[CP_USERS];
    uint8_t users, order[12 + CP_USERS], field, form_error;
    uint8_t shots;
    /* Games: only one runs at a time, so their states share storage. */
    uint8_t running, over;
    uint8_t game; /* the game screen whose state `g` holds (fills padding) */
    uint16_t game_ms;
    uint32_t score;
    union {
        struct {
            uint16_t body[64];
            uint8_t len, dir, last;
        } snake;
        struct {
            int16_t x, y, vx, vy, paddle;
        } pong;
        struct {
            uint8_t board[14][10];
            uint8_t piece, rot;
            int8_t x, y;
        } tetris;
    } g;
    uint16_t frames;
} cp_state;
extern cp_state cp;
void cp_init(void);
void cp_event(unsigned type, unsigned x, unsigned y, unsigned value);
/* Repaints only damaged regions; returns 1 when anything was drawn. */
int cp_render(void);
/* Marks the whole screen damaged, e.g. after the framebuffer was lost. */
void cp_invalidate(void);
/* Backend: clipped screen rectangles, individual pixels and completion.
 * The UI clips every call to its damage rectangle before calling these, so no
 * backend change is needed for partial repaint. */
void cp_fill(int x, int y, int w, int h, uint16_t color);
void cp_pixel(int x, int y, uint16_t color);
/* Transparent 5x8 glyph, clipped at the screen edges; scale 1..4. */
void cp_glyph(int x,
              int y,
              const uint8_t *glyph,
              uint16_t color,
              unsigned scale);
/* 48x32 image of 8-bit indices into a 256-entry RGB565 palette, row-major;
 * scale 2 or 4, even x, fully on screen (anything else draws nothing). */
void cp_bitmap8(int x,
                int y,
                const uint8_t *index,
                const uint16_t *palette,
                unsigned scale);
void cp_flush(void);
#endif
