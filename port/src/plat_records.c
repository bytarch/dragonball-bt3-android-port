/* bt3_battles.txt: one line per finished battle, appended next to the game's content (the folder the engine runs
   in - the app's own folder on Android, beside stages/ and songs/). The launcher reads it back to count matches
   and wins for the battle pass (android/app/src/main/java/com/bytarch/dbt3).

   Written from BattleResult_Finish (src/battle/battle_load.c), which runs once per battle as it is torn down,
   after the winner and reason are final. A battle left without a result (quit to the menu) carries only the abort
   bit and is not written. Line: mode|winner|reason|epoch; winner bits as in docs/systems/battle.md: 1 P1, 2 P2,
   4 draw, 8 abort (never written), 0x10 replay. */
#include <stdio.h>
#include <time.h>

void Port_RecordBattle(int mode, int winner, int reason) {
    FILE *f;

    if (!(winner & 7)) {
        return;
    }
    f = fopen("bt3_battles.txt", "a");
    if (f != NULL) {
        fprintf(f, "%d|%d|%d|%ld\n", mode, winner, reason, (long)time(NULL));
        fclose(f);
    }
}
