/* bt3_battles.txt: one line per finished battle, appended next to the game's content (the folder the engine runs
   in - the app's own folder on Android, beside stages/ and songs/). The launcher reads it back to count matches
   and wins for the battle pass (android/app/src/main/java/com/bytarch/dbt3).

   Written by the session's own watcher (port/src/headless.c, next to "the match is over") the moment a decided
   fight is seen, once per fight, never during a rollback's re-run. Not from BtlSeq_CheckBattleEnd
   (src/battle/btl_seq.c), where it first was: a fight's deciding call fills the winner and returns 1 in the same
   call, and the sequence leaves that state in the same tick, so no later call ever sees a winner and nothing was
   ever written. A fight left without a result (quit to the menu) carries only the abort bit and is not written.
   Line: mode|winner|reason|epoch|me; winner bits as in docs/systems/battle.md: 1 P1, 2 P2, 4 draw, 8 abort (never
   written), 0x10 replay; `me` is the side this copy played (0 the host, 1 the one who joined), so whoever reads the
   file back knows the fight from this device's point of view. */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

static int sRecorded; /* a fight of this session has been written (a judged one, or an abandoned one) */

void Port_RecordBattle(int mode, int winner, int reason, int me) {
    FILE *f;

    sRecorded = 1;
    fprintf(stderr, "bt3: battle record: mode=%d winner=%d reason=%d me=%d\n", mode, winner, reason, me); /* temporary */
    if (!(winner & 7)) {
        return;
    }
    f = fopen("bt3_battles.txt", "a");
    if (f == NULL) {
        fprintf(stderr, "bt3: battle record: no file: %s\n", strerror(errno)); /* temporary */
        return;
    }
    fprintf(f, "%d|%d|%d|%ld|%d\n", mode, winner, reason, (long)time(NULL), me);
    fclose(f);
}

/* Whether a judged fight has already written its line: the session's end asks, and only writes an abandoned match
   when the fight itself was never judged (a fight that was fought to the end is not written twice). */
int Port_RecordDecided(void) {
    return sRecorded;
}

/* A session that ended without a result: the other player went away ([won] 1, this side was left behind) or this
   player left ([won] 0). The line says who the fight would have gone to, with reason 8 (nobody was knocked out). */
void Port_RecordAbandon(int me, int won) {
    int side;

    if (sRecorded) {
        return;
    }
    side = won ? me : 1 - me; /* the side the fight would have gone to; winner bits are 1 for side 0, 2 for side 1 */
    Port_RecordBattle(0, side == 1 ? 2 : 1, 8, me);
}
