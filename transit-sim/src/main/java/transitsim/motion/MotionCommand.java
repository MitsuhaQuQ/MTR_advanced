package transitsim.motion;

/**
 * What a driver can tell a train. The host collects key presses during the tick and gives them to the train just before
 * it moves (decoupling document 3.7), through {@link TrainMotion#applyCommand(MotionState, MotionCommand)}.
 */
public enum MotionCommand {
	ACCELERATE,
	BRAKE,
	TOGGLE_DOORS
}
