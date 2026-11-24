package tn.smarthealthcare.orchestrator;

/**
 * Localisation du domicile du patient. Le jeu de données ne contient pas d'adresse : le domicile est simulé de façon
 * stable (même patient = même point) dans le Grand Tunis.
 */
public final class HomeLocation {

    private HomeLocation() {}

    private static final double LAT = 36.8065;
    private static final double LON = 10.1815;

    public static double[] of(String patientId) {
        int h = Math.abs(patientId.hashCode());
        double dLat = ((h % 997) / 997.0 - 0.5) * 0.10;
        double dLon = (((h / 997) % 991) / 991.0 - 0.5) * 0.10;
        return new double[] {LAT + dLat, LON + dLon};
    }
}
