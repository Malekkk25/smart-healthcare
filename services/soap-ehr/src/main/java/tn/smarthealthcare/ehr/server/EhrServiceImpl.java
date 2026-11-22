package tn.smarthealthcare.ehr.server;

import jakarta.jws.WebService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.apache.cxf.interceptor.Fault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tn.smarthealthcare.ehr.ActeCertifieType;
import tn.smarthealthcare.ehr.ComparerGarantiesRequest;
import tn.smarthealthcare.ehr.ComparerGarantiesResponse;
import tn.smarthealthcare.ehr.ConsulterHistoriqueRequest;
import tn.smarthealthcare.ehr.ConsulterHistoriqueResponse;
import tn.smarthealthcare.ehr.DifferenceGarantieType;
import tn.smarthealthcare.ehr.DroitsExpiresFault;
import tn.smarthealthcare.ehr.DroitsExpiresFault_Exception;
import tn.smarthealthcare.ehr.EhrPortType;
import tn.smarthealthcare.ehr.EmettreFeuilleDeSoinsRequest;
import tn.smarthealthcare.ehr.EmettreFeuilleDeSoinsResponse;
import tn.smarthealthcare.ehr.PatientInconnuFault;
import tn.smarthealthcare.ehr.PatientInconnuFault_Exception;
import tn.smarthealthcare.ehr.StatutFeuilleType;
import tn.smarthealthcare.ehr.VerifierDroitsCNAMRequest;
import tn.smarthealthcare.ehr.VerifierDroitsCNAMResponse;

@Service
@WebService(
        serviceName = "EhrService",
        portName = "EhrPort",
        targetNamespace = "http://smarthealthcare.tn/ehr",
        endpointInterface = "tn.smarthealthcare.ehr.EhrPortType",
        wsdlLocation = "classpath:wsdl/ehr.wsdl")
public class EhrServiceImpl implements EhrPortType {

    private static final Logger log = LoggerFactory.getLogger(EhrServiceImpl.class);
    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    private record PatientRow(String id, int taux, LocalDate finDroits) {}

    private final JdbcTemplate jdbc;

    public EhrServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 1. verifierDroitsCNAM

    @Override
    public VerifierDroitsCNAMResponse verifierDroitsCNAM(VerifierDroitsCNAMRequest in)
            throws PatientInconnuFault_Exception, DroitsExpiresFault_Exception {
        PatientRow patient = patientOuFault(in.getPatientId());
        verifierDroitsOuverts(patient);

        VerifierDroitsCNAMResponse out = new VerifierDroitsCNAMResponse();
        out.setTauxRemboursement(patient.taux());
        if (acteConnu(in.getCodeActe())) {
            out.setDroitsOuverts(true);
            out.setMotif("Droits ouverts jusqu'au " + patient.finDroits() + ", acte " + in.getCodeActe()
                    + " pris en charge à " + patient.taux() + "%");
        } else {
            out.setDroitsOuverts(false);
            out.setMotif("Acte " + in.getCodeActe() + " absent de la nomenclature : aucune prise en charge");
        }
        log.info("verifierDroitsCNAM patient={} acte={} -> droitsOuverts={}", in.getPatientId(), in.getCodeActe(),
                out.isDroitsOuverts());
        return out;
    }

    // ------------------------------------------------------------------ 2. emettreFeuilleDeSoins

    @Override
    public EmettreFeuilleDeSoinsResponse emettreFeuilleDeSoins(EmettreFeuilleDeSoinsRequest in)
            throws PatientInconnuFault_Exception, DroitsExpiresFault_Exception {
        PatientRow patient = patientOuFault(in.getPatientId());
        verifierDroitsOuverts(patient);

        boolean connu = acteConnu(in.getCodeActe());
        BigDecimal rembourse = connu
                ? in.getMontant().multiply(BigDecimal.valueOf(patient.taux())).divide(BigDecimal.valueOf(100), 3,
                        RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(3);
        StatutFeuilleType statut = connu ? StatutFeuilleType.EMISE : StatutFeuilleType.REJETEE;

        Long seq = jdbc.queryForObject("SELECT nextval('feuille_seq')", Long.class);
        String numero = String.format("FS-%d-%06d", LocalDate.now(TUNIS).getYear(), seq);
        jdbc.update("""
                INSERT INTO feuille_soins (numero, patient_id, code_acte, date_acte, montant, praticien_id,
                                           montant_rembourse, statut)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                numero, patient.id(), in.getCodeActe(), java.sql.Date.valueOf(toLocalDate(in.getDateActe())),
                in.getMontant(), in.getPraticienId(), rembourse, statut.value());

        EmettreFeuilleDeSoinsResponse out = new EmettreFeuilleDeSoinsResponse();
        out.setNumeroFeuille(numero);
        out.setMontantRembourse(rembourse);
        out.setStatut(statut);
        log.info("emettreFeuilleDeSoins patient={} acte={} -> {} {}", patient.id(), in.getCodeActe(), numero, statut);
        return out;
    }

    // ------------------------------------------------------------------ 3. consulterHistorique

    @Override
    public ConsulterHistoriqueResponse consulterHistorique(ConsulterHistoriqueRequest in)
            throws PatientInconnuFault_Exception {
        PatientRow patient = patientOuFault(in.getPatientId());

        ConsulterHistoriqueResponse out = new ConsulterHistoriqueResponse();
        jdbc.query("""
                SELECT code_acte, date_acte, montant, praticien_id FROM feuille_soins
                WHERE patient_id = ? AND statut = 'EMISE'
                ORDER BY date_acte DESC, numero DESC""", rs -> {
            ActeCertifieType acte = new ActeCertifieType();
            acte.setCode(rs.getString("code_acte"));
            acte.setDate(toXmlDate(rs.getDate("date_acte").toLocalDate()));
            acte.setMontant(rs.getBigDecimal("montant"));
            acte.setPraticien(rs.getString("praticien_id"));
            out.getActe().add(acte);
        }, patient.id());
        return out;
    }

    // ------------------------------------------------------------------ 4. comparerGaranties

    @Override
    public ComparerGarantiesResponse comparerGaranties(ComparerGarantiesRequest in)
            throws PatientInconnuFault_Exception, DroitsExpiresFault_Exception {
        PatientRow patient = patientOuFault(in.getPatientId());
        verifierDroitsOuverts(patient);

        Map<String, String> a = garanties(patient.id(), in.getPriseEnChargeIdA());
        Map<String, String> b = garanties(patient.id(), in.getPriseEnChargeIdB());

        Set<String> toutes = new LinkedHashSet<>(a.keySet());
        toutes.addAll(b.keySet());

        ComparerGarantiesResponse out = new ComparerGarantiesResponse();
        for (String garantie : toutes) {
            String va = a.getOrDefault(garantie, "non couvert");
            String vb = b.getOrDefault(garantie, "non couvert");
            if (!va.equals(vb)) {
                DifferenceGarantieType d = new DifferenceGarantieType();
                d.setGarantie(garantie);
                d.setValeurPriseEnChargeA(va);
                d.setValeurPriseEnChargeB(vb);
                out.getDifference().add(d);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ utilitaires

    private PatientRow patientOuFault(String patientId) throws PatientInconnuFault_Exception {
        return jdbc.query("SELECT id, taux_remboursement, date_fin_droits FROM patient WHERE id = ?",
                        (rs, i) -> new PatientRow(rs.getString("id"), rs.getInt("taux_remboursement"),
                                rs.getDate("date_fin_droits").toLocalDate()), patientId)
                .stream().findFirst().orElseThrow(() -> {
                    PatientInconnuFault detail = new PatientInconnuFault();
                    detail.setPatientId(patientId);
                    detail.setMessage("Aucun patient avec cet identifiant");
                    return new PatientInconnuFault_Exception("Patient inconnu", detail);
                });
    }

    private void verifierDroitsOuverts(PatientRow patient) throws DroitsExpiresFault_Exception {
        if (patient.finDroits().isBefore(LocalDate.now(TUNIS))) {
            DroitsExpiresFault detail = new DroitsExpiresFault();
            detail.setPatientId(patient.id());
            detail.setDateExpiration(toXmlDate(patient.finDroits()));
            detail.setMessage("Les droits CNAM du patient ont expiré");
            throw new DroitsExpiresFault_Exception("Droits expirés", detail);
        }
    }

    private boolean acteConnu(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM acte_nomenclature WHERE code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    private Map<String, String> garanties(String patientId, String priseEnChargeId) {
        Map<String, String> map = new LinkedHashMap<>();
        jdbc.query("SELECT garantie, valeur FROM prise_en_charge WHERE id = ? AND patient_id = ? ORDER BY garantie",
                rs -> {
                    map.put(rs.getString("garantie"), rs.getString("valeur"));
                }, priseEnChargeId, patientId);
        if (map.isEmpty()) {
            Fault fault = new Fault(new IllegalArgumentException(
                    "Prise en charge " + priseEnChargeId + " inconnue pour le patient " + patientId));
            fault.setFaultCode(Fault.FAULT_CODE_CLIENT);
            throw fault;
        }
        return map;
    }

    private static XMLGregorianCalendar toXmlDate(LocalDate date) {
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(date.toString());
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate toLocalDate(XMLGregorianCalendar c) {
        return LocalDate.of(c.getYear(), c.getMonth(), c.getDay());
    }
}
