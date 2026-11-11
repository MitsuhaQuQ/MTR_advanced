package mtr.data;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Score;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What other mods rely on in {@link TicketSystem} (0.68.0).
 */
class TicketSystemCompatTest {

	@Test
	void yjcmFindsTheMethodItsMixinInjectsInto() throws NoSuchMethodException {
		// YJCM 1.2.4's MixinTicketBarrier: @Inject(method = "onExit", at = HEAD) with defaultRequire 1, its handler taking
		// (Station, Player, Score, Score, boolean, CallbackInfoReturnable<Boolean>). Without exactly this the game won't start.
		final Method onExit = TicketSystem.class.getDeclaredMethod("onExit", Station.class, Player.class, Score.class, Score.class, boolean.class);
		assertEquals(boolean.class, onExit.getReturnType());
		assertTrue(Modifier.isStatic(onExit.getModifiers()));
		// Only one method of that name, so the injection can't pick another
		int count = 0;
		for (final Method method : TicketSystem.class.getDeclaredMethods()) {
			if (method.getName().equals("onExit")) {
				count++;
			}
		}
		assertEquals(1, count);
	}

	@Test
	void yjcmMachinesStillFindThePublicMembers() throws NoSuchMethodException {
		// Its enquiry and subsidy machines
		assertEquals(Score.class, TicketSystem.class.getMethod("getPlayerScore", Level.class, Player.class, String.class).getReturnType());
		TicketSystem.class.getMethod("addObjectivesIfMissing", Level.class);
		assertEquals("mtr_balance", TicketSystem.BALANCE_OBJECTIVE);
	}

	@Test
	void aBalanceKeptByNameIsAddedAndOtherScoresReplaced() {
		// A command adding to a name's balance after the move to UUIDs still reaches the player
		assertEquals(150, TicketSystem.mergeScore(100, 50, true));
		assertEquals(Integer.MAX_VALUE, TicketSystem.mergeScore(Integer.MAX_VALUE, 5, true));
		assertEquals(Integer.MIN_VALUE, TicketSystem.mergeScore(Integer.MIN_VALUE, -5, true));
		assertEquals(7, TicketSystem.mergeScore(3, 7, false));
	}

	@Test
	void theFareSaverTakesOffNoMoreThanTheFare() {
		// As YJCM: min(discount, fare); a negative discount (its "sarcasm") is added
		assertEquals(2, TicketSystem.fareSaverSaving(2, 9));
		assertEquals(3, TicketSystem.fareSaverSaving(5, 3));
		assertEquals(-4, TicketSystem.fareSaverSaving(-4, 3));
	}
}
