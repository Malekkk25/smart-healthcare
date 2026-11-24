package tn.smarthealthcare.ehr.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import javax.xml.datatype.DatatypeFactory;
import org.apache.cxf.interceptor.Fault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tn.smarthealthcare.ehr.ComparerGarantiesRequest;
import tn.smarthealthcare.ehr.ConsulterHistoriqueRequest;
import tn.smarthealthcare.ehr.DroitsExpiresFault_Exception;
import tn.smarthealthcare.ehr.EmettreFeuilleDeSoinsRequest;
import tn.smarthealthcare.ehr.PatientInconnuFault_Exception;
import tn.smarthealthcare.ehr.StatutFeuilleType;
import tn.smarthealthcare.ehr.VerifierDroitsCNAMRequest;

/** Logique métier du service SOAP, sur une base H2 en mémoire (mode PostgreSQL) avec le vrai schema.sql. */
class EhrServiceImplTest {

    private JdbcTemplate jdbc;
    private EhrServiceImpl service;

    @BeforeEach
    void init() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:ehr" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        // H2 ne connaît pas TIMESTAMPTZ (type PostgreSQL) : on déclare un alias pour réutiliser le vrai schema.sql
        new JdbcTemplate(ds).execute("CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO patient VALUES ('PAT-000001','A','A','111',80,'2099-12-31')");
        jdbc.update("INSERT INTO patient VALUES ('PAT-000002','B','B','222',50,'2099-12-31')");
        jdbc.update("INSERT INTO patient VALUES ('PAT-000010','C','C','333',80,'2020-01-01')"); // droits expirés
        jdbc.update("INSERT INTO acte_nomenclature VALUES ('CS','Consultation spécialiste',35.000)");
        jdbc.update("INSERT INTO acte_nomenclature VALUES ('K','Acte de spécialité',120.000)");
        jdbc.update("INSERT INTO prise_en_charge VALUES ('PEC-A','PAT-000001','Optique','non couvert')");
        jdbc.update("INSERT INTO prise_en_charge VALUES ('PEC-A','PAT-000001','Consultation','80%')");
        jdbc.update("INSERT INTO prise_en_charge VALUES ('PEC-B','PAT-000001','Optique','50%')");
        jdbc.update("INSERT INTO prise_en_charge VALUES ('PEC-B','PAT-000001','Consultation','80%')");
        service = new EhrServiceImpl(jdbc);
    }

    private static VerifierDroitsCNAMRequest droits(String patient, String acte) {
        var r = new VerifierDroitsCNAMRequest();
        r.setPatientId(patient);
        r.setCodeActe(acte);
        return r;
    }

    private static EmettreFeuilleDeSoinsRequest feuille(String patient, String acte, String montant) throws Exception {
        var r = new EmettreFeuilleDeSoinsRequest();
        r.setPatientId(patient);
        r.setCodeActe(acte);
        r.setDateActe(DatatypeFactory.newInstance().newXMLGregorianCalendar(LocalDate.of(2026, 11, 20).toString()));
        r.setMontant(new BigDecimal(montant));
        r.setPraticienId("DOC-001");
        return r;
    }

    @Test
    void droitsOuvertsPourUnActeConnu() throws Exception {
        var r = service.verifierDroitsCNAM(droits("PAT-000001", "CS"));
        assertTrue(r.isDroitsOuverts());
        assertEquals(80, r.getTauxRemboursement());
    }

    @Test
    void tauxDuPatientEstRepris() throws Exception {
        assertEquals(50, service.verifierDroitsCNAM(droits("PAT-000002", "CS")).getTauxRemboursement());
    }

    @Test
    void acteHorsNomenclatureNEstPasPrisEnCharge() throws Exception {
        var r = service.verifierDroitsCNAM(droits("PAT-000001", "XX"));
        assertFalse(r.isDroitsOuverts());
        assertTrue(r.getMotif().contains("XX"));
    }

    @Test
    void patientInconnuLeveUnFaultTypé() {
        var e = assertThrows(PatientInconnuFault_Exception.class, () -> service.verifierDroitsCNAM(droits("PAT-000099", "CS")));
        assertEquals("PAT-000099", e.getFaultInfo().getPatientId());
    }

    @Test
    void droitsExpiresLeveUnFaultAvecLaDate() {
        var e = assertThrows(DroitsExpiresFault_Exception.class, () -> service.verifierDroitsCNAM(droits("PAT-000010", "CS")));
        assertEquals("PAT-000010", e.getFaultInfo().getPatientId());
        assertEquals(2020, e.getFaultInfo().getDateExpiration().getYear());
    }

    @Test
    void feuilleDeSoinsCalculeLeRemboursement() throws Exception {
        var r = service.emettreFeuilleDeSoins(feuille("PAT-000001", "CS", "35.000"));
        assertEquals(StatutFeuilleType.EMISE, r.getStatut());
        assertEquals(0, new BigDecimal("28.000").compareTo(r.getMontantRembourse())); // 35 x 80 %
        assertTrue(r.getNumeroFeuille().matches("FS-\\d{4}-\\d{6}"));
    }

    @Test
    void feuilleSurActeInconnuEstRejetee() throws Exception {
        var r = service.emettreFeuilleDeSoins(feuille("PAT-000001", "XX", "50.000"));
        assertEquals(StatutFeuilleType.REJETEE, r.getStatut());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getMontantRembourse()));
    }

    @Test
    void feuilleRefuseeSiDroitsExpires() {
        assertThrows(DroitsExpiresFault_Exception.class, () -> service.emettreFeuilleDeSoins(feuille("PAT-000010", "CS", "35.000")));
    }

    @Test
    void historiqueNeContientQueLesActesCertifies() throws Exception {
        service.emettreFeuilleDeSoins(feuille("PAT-000001", "CS", "35.000"));
        service.emettreFeuilleDeSoins(feuille("PAT-000001", "XX", "10.000")); // rejetée : absente de l'historique
        var req = new ConsulterHistoriqueRequest();
        req.setPatientId("PAT-000001");
        var h = service.consulterHistorique(req);
        assertEquals(1, h.getActe().size());
        assertEquals("CS", h.getActe().get(0).getCode());
    }

    @Test
    void comparerGarantiesNeRenvoieQueLesDifferences() throws Exception {
        var req = new ComparerGarantiesRequest();
        req.setPatientId("PAT-000001");
        req.setPriseEnChargeIdA("PEC-A");
        req.setPriseEnChargeIdB("PEC-B");
        var r = service.comparerGaranties(req);
        assertEquals(1, r.getDifference().size());
        assertEquals("Optique", r.getDifference().get(0).getGarantie());
        assertEquals("non couvert", r.getDifference().get(0).getValeurPriseEnChargeA());
        assertEquals("50%", r.getDifference().get(0).getValeurPriseEnChargeB());
    }

    @Test
    void comparerUnePriseEnChargeInconnueEstUneErreurClient() {
        var req = new ComparerGarantiesRequest();
        req.setPatientId("PAT-000001");
        req.setPriseEnChargeIdA("PEC-A");
        req.setPriseEnChargeIdB("PEC-INCONNUE");
        assertThrows(Fault.class, () -> service.comparerGaranties(req));
    }
}
