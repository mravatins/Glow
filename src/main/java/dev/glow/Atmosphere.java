package dev.glow;

/** The sun's colour after the air it crosses, from the same atmosphere the sky tables in shadow.comp use. */
final class Atmosphere {
	private static final double EARTH_RADIUS = 6360.0e3;
	private static final double ATMOSPHERE_RADIUS = 6460.0e3;
	private static final double[] RAYLEIGH = {5.802e-6, 13.558e-6, 33.1e-6};
	private static final double RAYLEIGH_HEIGHT = 8000.0;
	private static final double MIE_EXTINCTION = 4.4e-6;
	private static final double MIE_HEIGHT = 1200.0;
	private static final double[] OZONE = {0.650e-6, 1.881e-6, 0.085e-6};
	private static final double ALTITUDE = 200.0;
	private static final int STEPS = 48;
	// Below this elevation the sun is too low to light anything, and the lighting's own horizon fade takes over.
	private static final double MIN_ELEVATION_SINE = 0.01;
	private static final double[] NOON = transmittance(1.0);

	private Atmosphere() {}

	/** The sun's colour at a height in the sky, given as the sine of its elevation, scaled so noon is white with its red at 1. */
	static float[] sunColor(float elevationSine) {
		double[] t = transmittance(Math.max(elevationSine, MIN_ELEVATION_SINE));
		return new float[] {(float) (t[0] / NOON[0]), (float) (t[1] / NOON[0]), (float) (t[2] / NOON[0])};
	}

	private static double[] transmittance(double elevationSine) {
		double cosine = Math.sqrt(Math.max(1.0 - elevationSine * elevationSine, 0.0));
		double originY = EARTH_RADIUS + ALTITUDE;
		// Distance along the ray to the top of the atmosphere.
		double b = originY * elevationSine, c = originY * originY - ATMOSPHERE_RADIUS * ATMOSPHERE_RADIUS;
		double reach = -b + Math.sqrt(Math.max(b * b - c, 0.0));
		double dt = reach / STEPS;
		double[] depth = new double[3];
		for (int i = 0; i < STEPS; i++) {
			double t = (i + 0.5) * dt;
			double x = cosine * t, y = originY + elevationSine * t;
			double height = Math.sqrt(x * x + y * y) - EARTH_RADIUS;
			double rayleigh = Math.exp(-height / RAYLEIGH_HEIGHT), mie = Math.exp(-height / MIE_HEIGHT);
			double ozone = Math.max(0.0, 1.0 - Math.abs(height - 25.0e3) / 15.0e3);
			for (int k = 0; k < 3; k++) depth[k] += (RAYLEIGH[k] * rayleigh + MIE_EXTINCTION * mie + OZONE[k] * ozone) * dt;
		}
		return new double[] {Math.exp(-depth[0]), Math.exp(-depth[1]), Math.exp(-depth[2])};
	}
}
