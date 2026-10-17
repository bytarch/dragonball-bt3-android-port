/* bt3_battles.txt: one line per finished battle, appended next to the game's content (the folder the engine runs
   in - the app's own folder on Android, beside stages/ and songs/). The launcher reads it back to count matches
   and wins for the battle pass (android/app/src/main/java/com/bytarch/dbt3).

   Written from BtlSeq_CheckBattleEnd (src/battle/btl_seq.c) the moment a fight is decided, once per fight - mid-
   session fights write their line too, not only what a whole battle session leaves behind. A fight left without a
   result (quit to the menu) carries only the abort bit and is not written. Line: mode|winner|reason|epoch; winner
   bits as in docs/systems/battle.md: 1 P1, 2 P2, 4 draw, 8 abort (never written), 0x10 replay. */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

void Port_RecordBattle(int mode, int winner, int reason) {
    FILE *f;

    fprintf(stderr, "bt3: battle record: mode=%d winner=%d reason=%d\n", mode, winner, reason); /* temporary */
    if (!(winner & 7)) {
        return;
    }
    f = fopen("bt3_battles.txt", "a");
    if (f == NULL) {
        fprintf(stderr, "bt3: battle record: no file: %s\n", strerror(errno)); /* temporary */
        return;
    }
    fprintf(f, "%d|%d|%d|%ld\n", mode, winner, reason, (long)time(NULL));
    fclose(f);
}
