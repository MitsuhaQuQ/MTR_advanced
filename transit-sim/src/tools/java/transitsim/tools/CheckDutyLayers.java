package transitsim.tools;

import transitsim.plan.DepotDuties;
import transitsim.plan.DutyLayers;
import transitsim.sim.SimDataset;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Splits every depot's duties of a world into duty layers ({@link DutyLayers}) and back, says whether each comes back
 * exactly, and how much is the same across depots (duty groups, step 1, measured on real worlds).
 * <pre>
 *   ./gradlew -p transit-sim checkDutyLayers --args="path/to/mtr.db"
 * </pre>
 */
public final class CheckDutyLayers {

	public static void main(String[] args) throws Exception {
		final byte[] world;
		try (final Connection connection = DriverManager.getConnection("jdbc:sqlite:" + args[0]); final PreparedStatement statement = connection.prepareStatement("SELECT data FROM sim_data WHERE key = 'world'"); final ResultSet resultSet = statement.executeQuery()) {
			if (!resultSet.next()) {
				System.out.println("No simulation data in " + args[0]);
				return;
			}
			world = resultSet.getBytes(1);
		}
		final SimDataset dataset = SimDataset.fromBytes(world);
		final Map<Long, String> pools = new HashMap<>();
		dataset.sidings.forEach(siding -> pools.put(siding.id, siding.poolKey));
		int depots = 0;
		int same = 0;
		int rows = 0;
		long dutyBytes = 0;
		long assignmentBytes = 0;
		final Map<Long, DutyLayers.Body> bodies = new HashMap<>();
		final Map<Long, DutyLayers.Group> groups = new HashMap<>();
		final List<String> different = new ArrayList<>();
		for (final SimDataset.DepotData depot : dataset.depots) {
			if (depot.duties.length == 0) {
				continue;
			}
			final DepotDuties duties = new DepotDuties();
			duties.fromBytes(depot.duties);
			if (duties.isEmpty()) {
				continue;
			}
			depots++;
			final DutyLayers layers = DutyLayers.of(duties, sidingId -> pools.getOrDefault(sidingId, ""));
			final DepotDuties back = layers.toDuties();
			if (back != null && Arrays.equals(back.toBytes(), duties.toBytes())) {
				same++;
			} else {
				different.add(String.valueOf(depot.id));
			}
			rows += layers.assignments.size();
			dutyBytes += duties.toBytes().length;
			bodies.putAll(layers.bodies);
			groups.putAll(layers.groups);
			final DutyLayers assignmentsOnly = new DutyLayers();
			assignmentsOnly.assignments.addAll(layers.assignments);
			assignmentBytes += assignmentsOnly.toBytes().length;
		}
		final DutyLayers shared = new DutyLayers();
		shared.bodies.putAll(bodies);
		shared.groups.putAll(groups);
		final long sharedBytes = shared.toBytes().length;
		System.out.println("Depots with duties: " + depots + ", back exactly: " + same + (different.isEmpty() ? "" : ", not: " + different));
		System.out.println("Duty rows (slot and weekdays): " + rows + ", bodies: " + bodies.size() + ", groups: " + groups.size());
		System.out.println("Duties as saved: " + dutyBytes / 1000 + " kB; as layers: bodies and groups once " + sharedBytes / 1000 + " kB + assignments " + assignmentBytes / 1000 + " kB");
	}
}
