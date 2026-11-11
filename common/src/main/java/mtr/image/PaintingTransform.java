package mtr.image;

/** Local X, then Y, then Z rotations in degrees, shared by geometry, lighting and the editor. */
public final class PaintingTransform {
	private PaintingTransform() { }
	public static double[] rotate(double x, double y, double z, double rx, double ry, double rz) {
		double a = Math.toRadians(rx), b = Math.toRadians(ry), c = Math.toRadians(rz);
		double y1 = y * Math.cos(a) - z * Math.sin(a), z1 = y * Math.sin(a) + z * Math.cos(a);
		double x2 = x * Math.cos(b) + z1 * Math.sin(b), z2 = -x * Math.sin(b) + z1 * Math.cos(b);
		return new double[]{x2 * Math.cos(c) - y1 * Math.sin(c), x2 * Math.sin(c) + y1 * Math.cos(c), z2};
	}
	public static double wrap(double angle) { return ((angle + 180) % 360 + 360) % 360 - 180; }
}
