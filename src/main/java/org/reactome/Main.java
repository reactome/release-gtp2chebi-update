package org.reactome;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.Schema;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Joel Weiser (joel.weiser@oicr.on.ca)
 * Created 1/30/2025
 */
public class Main {
    private static GKInstance createdInstanceEdit;
    private static MySQLAdaptor dba;

    public static void main(String[] args) throws Exception {
        FileDownloader fileDownloader = new FileDownloader();
        fileDownloader.downloadFiles();

        dba = createDba();
        Map<String, GKInstance> referenceTherapeutics = getGtoP2ReferenceTherapeutics();
        Map<String, Set<String>> gToP2ChEBI = getGuideToPharmacology2ChEBIMapping();

        for (String gToPId : gToP2ChEBI.keySet()) {
            GKInstance referenceTherapeutic = referenceTherapeutics.get(gToPId);

            if (referenceTherapeutic != null) {
                dba.loadInstanceAttributeValues(referenceTherapeutic);

                Set<String> chEBIIds = gToP2ChEBI.get(gToPId);
                if (!hasChEBICrossReference(referenceTherapeutic)) {
                    addChEBICrossReferences(referenceTherapeutic, chEBIIds);
                } else {
                    updateChEBICrossReferences(referenceTherapeutic, chEBIIds);
                }
            }
        }
    }

    private static Map<String, GKInstance> getGtoP2ReferenceTherapeutics() throws Exception {
        Collection<GKInstance> referenceTherapeutics =
            getDba().fetchInstancesByClass(ReactomeJavaConstants.ReferenceTherapeutic);

        Map<String, GKInstance> gToP2ReferenceTherapeutics = new HashMap<>();
        for (GKInstance referenceTherapeutic : referenceTherapeutics) {
            if (hasGuideToPharmacologyReferenceDatabase(referenceTherapeutic)) {
                String gToPIdentifier = (String) referenceTherapeutic.getAttributeValue(ReactomeJavaConstants.identifier);
                gToP2ReferenceTherapeutics.put(gToPIdentifier, referenceTherapeutic);
            }
        }
        return gToP2ReferenceTherapeutics;
    }

    private static boolean hasGuideToPharmacologyReferenceDatabase(GKInstance referenceTherapeutic) throws Exception {
        GKInstance referenceDatabase = (GKInstance) referenceTherapeutic.getAttributeValue(ReactomeJavaConstants.referenceDatabase);

        return referenceDatabase.getDisplayName().equalsIgnoreCase("Guide to Pharmacology - Ligands");
    }

    private static boolean hasChEBICrossReference(GKInstance referenceTherapeutic) throws Exception {
        return !getChEBICrossReferences(referenceTherapeutic).isEmpty();
    }

    private static void addChEBICrossReference(GKInstance referenceTherapeutic, String chEBIId) throws Exception {
        addChEBICrossReferences(referenceTherapeutic, Collections.singleton(chEBIId));
    }

    private static void addChEBICrossReferences(GKInstance referenceTherapeutic, Set<String> chEBIIds) throws Exception {
        if (chEBIIds != null && !chEBIIds.isEmpty()) {
            for (String chEBIId : chEBIIds) {
                referenceTherapeutic.addAttributeValue(
                    ReactomeJavaConstants.crossReference,
                    createDatabaseIdentifier(chEBIId)
                );
            }
            addModifiedInstanceEdit(referenceTherapeutic);
            report(referenceTherapeutic, chEBIIds, "addition");

            if (referenceTherapeutic.getDbAdaptor() == null) {
                System.out.println(chEBIIds);
                return;
            }
            getDba().updateInstanceAttribute(referenceTherapeutic, ReactomeJavaConstants.crossReference);
            getDba().updateInstanceAttribute(referenceTherapeutic, ReactomeJavaConstants.modified);
        }
    }

    private static void updateChEBICrossReferences(GKInstance referenceTherapeutic, Set<String> chEBIIds)
        throws Exception {

        boolean updated = false;
        Map<String, GKInstance> chEBIDatabaseIdentifiers = getChEBICrossReferences(referenceTherapeutic);
        for (String chEBIId : chEBIIds) {
            if (!chEBIDatabaseIdentifiers.containsKey(chEBIId)) {
                addChEBICrossReference(referenceTherapeutic, chEBIId);
                updated = true;
            } else {
                // Should be kept in database, so remove from map used to delete obsolete database identifiers
                chEBIDatabaseIdentifiers.remove(chEBIId);
            }
        }

        if (!chEBIDatabaseIdentifiers.isEmpty()) {
            removeChEBICrossReferences(referenceTherapeutic, chEBIDatabaseIdentifiers);
            updated = true;
        }

        if (updated) {
            addModifiedInstanceEdit(referenceTherapeutic);

            getDba().updateInstanceAttribute(referenceTherapeutic, ReactomeJavaConstants.crossReference);
            getDba().updateInstanceAttribute(referenceTherapeutic, ReactomeJavaConstants.modified);
        }
    }

    private static Map<String, GKInstance> getChEBICrossReferences(GKInstance referenceTherapeutic) throws Exception {
        Map<String, GKInstance> chEBICrossReferences = new HashMap<>();

        Collection<GKInstance> crossReferences =
            referenceTherapeutic.getAttributeValuesList(ReactomeJavaConstants.crossReference);
        for (GKInstance crossReference : crossReferences) {
            GKInstance crossReferenceReferenceDatabase =
                (GKInstance) crossReference.getAttributeValue(ReactomeJavaConstants.referenceDatabase);
            if (crossReferenceReferenceDatabase.getDisplayName().equals("ChEBI")) {
                String chEBIIdentifier = (String) crossReference.getAttributeValue(ReactomeJavaConstants.identifier);
                chEBICrossReferences.put(chEBIIdentifier, crossReference);
            }
        }

        return chEBICrossReferences;
    }

    private static void removeChEBICrossReferences(
        GKInstance referenceTherapeutic, Map<String, GKInstance> chEBIDatabaseIdentifiers) {

        for (GKInstance chEBIDatabaseIdentifier : chEBIDatabaseIdentifiers.values()) {
            referenceTherapeutic.removeAttributeValueNoCheck(
                ReactomeJavaConstants.crossReference, chEBIDatabaseIdentifier);
        }

        report(referenceTherapeutic, chEBIDatabaseIdentifiers.keySet(), "removal");
    }

    private static void addModifiedInstanceEdit(GKInstance referenceTherapeutic) throws Exception {
        referenceTherapeutic.addAttributeValue(ReactomeJavaConstants.modified, getCreatedInstanceEdit());
    }

    private static GKInstance createDatabaseIdentifier(String chEBIId) throws Exception {
        GKInstance databaseIdentifier =
            new GKInstance(getSchema().getClassByName(ReactomeJavaConstants.DatabaseIdentifier));
        
        databaseIdentifier.setDbAdaptor(getDba());
        databaseIdentifier.setAttributeValue(ReactomeJavaConstants.referenceDatabase, getChEBIReferenceDatabase());
        databaseIdentifier.setAttributeValue(ReactomeJavaConstants.identifier, chEBIId);
        databaseIdentifier.setAttributeValue(ReactomeJavaConstants.created, getCreatedInstanceEdit());
        InstanceDisplayNameGenerator.setDisplayName(databaseIdentifier);

        return databaseIdentifier;
    }

    private static GKInstance getChEBIReferenceDatabase() throws Exception {
        return (GKInstance) getDba().fetchInstanceByAttribute(
            ReactomeJavaConstants.ReferenceDatabase, ReactomeJavaConstants._displayName, "=", "ChEBI"
        ).iterator().next();
    }

    private static GKInstance getCreatedInstanceEdit() throws Exception {
        if (createdInstanceEdit == null) {
            createdInstanceEdit = new GKInstance(getSchema().getClassByName(ReactomeJavaConstants.InstanceEdit));
            createdInstanceEdit.addAttributeValue(ReactomeJavaConstants.author, getPersonInstance());
            createdInstanceEdit.addAttributeValue(ReactomeJavaConstants.dateTime, getCurrentDateTime());
            createdInstanceEdit.addAttributeValue(ReactomeJavaConstants.note, "Added ChEBI cross-references to ReferenceTherapeutics");
            InstanceDisplayNameGenerator.setDisplayName(createdInstanceEdit);
        }
        return createdInstanceEdit;
    }

    private static GKInstance getPersonInstance() throws Exception {
        long personDbId = Long.parseLong(getConfigProperties().getProperty("personId"));

        return getDba().fetchInstance(personDbId);
    }

    private static String getCurrentDateTime() {
        return ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss"));
    }

    private static MySQLAdaptor createDba() throws SQLException, IOException {
        Properties configProperties = getConfigProperties();

        return new MySQLAdaptor(
            configProperties.getProperty("host", "localhost"),
            configProperties.getProperty("dbName"),
            configProperties.getProperty("user", "root"),
            configProperties.getProperty("pass", "root")
        );
    }

    private static Properties getConfigProperties() throws IOException {
        Properties props = new Properties();
        props.load(Main.class.getClassLoader().getResourceAsStream("config.properties"));
        return props;
    }

    private static Map<String, Set<String>> getGuideToPharmacology2ChEBIMapping() throws IOException {
        Map<String, String> guideToPharmacology2PubChem = processGuideToPharmacologyFile();
        Map<String, Set<String>> pubChemToChEBI = processPubChemFile();

        Map<String, Set<String>> guideToPharmacology2ChEBI = new HashMap<>();
        for (Map.Entry<String, String> entry : guideToPharmacology2PubChem.entrySet()) {
            String guideToPharmacologyId = entry.getKey();
            String pubChemId = entry.getValue();
            Set<String> chEBIIds = pubChemToChEBI.get(pubChemId);

            if (chEBIIds != null && !chEBIIds.isEmpty()) {
                guideToPharmacology2ChEBI.computeIfAbsent(guideToPharmacologyId, k -> new HashSet<>())
                    .addAll(chEBIIds);
            }
        }

        return guideToPharmacology2ChEBI;
    }

    private static Schema getSchema() throws Exception {
        if (getDba().getSchema() == null) {
            getDba().fetchSchema();
        }

        return getDba().getSchema();
    }

    private static Map<String, String> processGuideToPharmacologyFile() {
        Map<String, String> guideToPharmacology2PubChem = new HashMap<>();

        try(CSVParser parser = getGtoPCSVParser(getGuideToPharmacologyFileBufferedReader())) {
            parser.forEach(record -> guideToPharmacology2PubChem.put(
                record.get("Ligand ID"),
                record.get("PubChem CID")
            ));
        } catch (IOException e) {
            throw new RuntimeException("Unable to parse Guide To Pharmacology file", e);
        }

        return guideToPharmacology2PubChem;
    }

    private static Map<String, Set<String>> processPubChemFile() throws IOException {
        Map<String, Set<String>> pubChem2ChEBI = new HashMap<>();

        BufferedReader pubChemCompoundFileBufferedReader = getPubChemCompoundFileBufferedReader();
        String line;
        while ((line = pubChemCompoundFileBufferedReader.readLine()) != null) {
            String[] columns = line.split("\t");

            String cid = columns[0];
            String chEBIId = extractChEBIId(columns[1]);

            if (!chEBIId.isEmpty()) {
                pubChem2ChEBI.computeIfAbsent(cid, k -> new HashSet<>()).add(chEBIId);
            }
        }

        return pubChem2ChEBI;
    }

    private static String extractChEBIId(String chEBIIdColumn) {
        Pattern chEBIIdPattern = Pattern.compile("CHEBI:(\\d+).*");
        Matcher chEBIIdMatcher = chEBIIdPattern.matcher(chEBIIdColumn);

        if (!chEBIIdMatcher.find()) {
            return "";
        }
        return chEBIIdMatcher.group(1);
    }

    private static CSVParser getGtoPCSVParser(BufferedReader csvFileBufferedReader) throws IOException {
        return new CSVParser(
            getReaderAfterVersionHeader(csvFileBufferedReader),
            CSVFormat.DEFAULT.withFirstRecordAsHeader()
        );
    }

    private static Reader getReaderAfterVersionHeader(BufferedReader reader) throws IOException {
        String header = reader.readLine();
        if (!header.contains("GtoPdb Version")) {
            reader.reset(); // Reset to beginning to include first line if not version header
        }
        return reader; // Return reader at proper position after version header, if exists
    }

    private static void report(GKInstance referenceTherapeutic, Set<String> chEBIIds, String typeOfChange) {
        System.out.println(
            String.join("\t",
                referenceTherapeutic.getDBID().toString(),
                referenceTherapeutic.getDisplayName(),
                "ChEBI Id(s):" + chEBIIds,
                typeOfChange
            )
        );
    }

    private static BufferedReader getGuideToPharmacologyFileBufferedReader() throws IOException {
        return getBufferedReader("ligand_id_mapping.csv");
    }

    private static BufferedReader getPubChemCompoundFileBufferedReader() throws IOException {
        return getBufferedReader("pubchem_id_mapping.tsv");
    }

    private static BufferedReader getBufferedReader(String fileName) throws IOException {
        return Files.newBufferedReader(Paths.get(fileName));
    }

    private static MySQLAdaptor getDba() {
        return dba;
    }
}