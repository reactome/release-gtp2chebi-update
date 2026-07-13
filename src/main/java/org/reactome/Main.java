package org.reactome;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Joel Weiser (joel.weiser@oicr.on.ca)
 * Created 1/30/2025
 */
public class Main {
    private static final Logger logger = LogManager.getLogger();

    private CuratorToolAPI curatorToolAPI;

    @Parameter(names = {"-c", "--config"})
    private String configFilePath = "src/main/resources/config.properties";

    @Parameter(names = {"-o", "--output"})
    private String outputFilePath = "./cross_reference_update.txt";

    public static void main(String[] args) throws Exception {
        Main main = new Main();
        JCommander.newBuilder()
            .addObject(main)
            .build()
            .parse(args);
        main.run();
    }

    public void run() throws Exception {
        curatorToolAPI = new CuratorToolAPI();

        FileDownloader fileDownloader = new FileDownloader();
        fileDownloader.downloadFiles();

        Map<String, SimpleInstance> referenceTherapeutics = getGtoP2ReferenceTherapeutics();
        Map<String, Set<String>> gToP2ChEBI = getGuideToPharmacology2ChEBIMapping();

        writeFileHeader();
        logger.info("Beginning cross-reference check of Reference Therapeutic instances...");
        for (String gToPId : gToP2ChEBI.keySet()) {
            SimpleInstance referenceTherapeutic = referenceTherapeutics.get(gToPId);

            if (referenceTherapeutic != null) {
                logger.debug("Checking {}", referenceTherapeutic.getDisplayName());

                Set<String> chEBIIds = gToP2ChEBI.get(gToPId);

                boolean addedOrUpdated;
                if (!hasChEBICrossReference(referenceTherapeutic)) {
                    addedOrUpdated = addChEBICrossReferences(referenceTherapeutic, chEBIIds);
                } else {
                    addedOrUpdated = updateChEBICrossReferences(referenceTherapeutic, chEBIIds);
                }

                if (addedOrUpdated) {
                    updateInDb(referenceTherapeutic);
                }
            }
        }
        curatorToolAPI.close();
        logger.info("Finished cross-reference check of Reference Therapeutic instances");
    }

    private Map<String, SimpleInstance> getGtoP2ReferenceTherapeutics() throws Exception {
        List<SimpleInstance> referenceTherapeutics = curatorToolAPI.fetchAllReferenceTherapeutics();

        Map<String, SimpleInstance> gToP2ReferenceTherapeutics = new HashMap<>();
        for (SimpleInstance referenceTherapeutic : referenceTherapeutics) {
            if (hasGuideToPharmacologyReferenceDatabase(referenceTherapeutic)) {
                String gToPIdentifier = (String) referenceTherapeutic.getAttribute(ReactomeJavaConstants.identifier);
                gToP2ReferenceTherapeutics.put(gToPIdentifier, referenceTherapeutic);
            }
        }
        return gToP2ReferenceTherapeutics;
    }

    private boolean hasGuideToPharmacologyReferenceDatabase(SimpleInstance referenceTherapeutic) throws Exception {
        SimpleInstance referenceDatabase = (SimpleInstance) referenceTherapeutic.getAttribute(ReactomeJavaConstants.referenceDatabase);

        return referenceDatabase.getDisplayName().equalsIgnoreCase("Guide to Pharmacology - Ligands");
    }

    private void writeFileHeader() throws IOException {
        String outputLine = String.join("\t",
                "Reference Therapeutic DbId",
                "Reference Therapeutic DisplayName",
                "ChEBI Ids",
                "Type of change"
            ).concat(System.lineSeparator());

        Files.write(
            getOutputFilePath(),
            outputLine.getBytes(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private boolean hasChEBICrossReference(SimpleInstance referenceTherapeutic) {
        return !getChEBICrossReferences(referenceTherapeutic).isEmpty();
    }

    private boolean addChEBICrossReference(SimpleInstance referenceTherapeutic, String chEBIId) throws Exception {
        return addChEBICrossReferences(referenceTherapeutic, Collections.singleton(chEBIId));
    }

    private boolean addChEBICrossReferences(SimpleInstance referenceTherapeutic, Set<String> chEBIIds) throws Exception {
        if (chEBIIds != null && !chEBIIds.isEmpty()) {
            for (String chEBIId : chEBIIds) {
                logger.info("Adding ChEBI:" + chEBIId + " cross-reference to " + referenceTherapeutic);
                List<SimpleInstance> crossReferences =
                    (List<SimpleInstance>) referenceTherapeutic.getAttribute(ReactomeJavaConstants.crossReference);
                crossReferences.add(createDatabaseIdentifier(chEBIId));

                referenceTherapeutic.setAttribute(ReactomeJavaConstants.crossReference, crossReferences);
            }
            report(referenceTherapeutic, chEBIIds, "addition");

            return true;
        }
        return false;
    }

    private boolean updateChEBICrossReferences(SimpleInstance referenceTherapeutic, Set<String> chEBIIds)
        throws Exception {

        boolean updated = false;
        Map<String, SimpleInstance> chEBIDatabaseIdentifiers = getChEBICrossReferences(referenceTherapeutic);
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

        return updated;
    }

    private Map<String, SimpleInstance> getChEBICrossReferences(SimpleInstance referenceTherapeutic) {
        Map<String, SimpleInstance> chEBICrossReferences = new HashMap<>();

        List<SimpleInstance> crossReferences =
            (List<SimpleInstance>) referenceTherapeutic.getAttribute(ReactomeJavaConstants.crossReference);
        for (SimpleInstance crossReference : crossReferences) {
            crossReference = curatorToolAPI.inflate(crossReference);

            SimpleInstance crossReferenceReferenceDatabase =
                (SimpleInstance) crossReference.getAttribute(ReactomeJavaConstants.referenceDatabase);
            if (crossReferenceReferenceDatabase.getDisplayName().equals("ChEBI")) {
                String chEBIIdentifier = (String) crossReference.getAttribute(ReactomeJavaConstants.identifier);
                chEBICrossReferences.put(chEBIIdentifier, crossReference);
            }
        }

        return chEBICrossReferences;
    }

    private void removeChEBICrossReferences(
        SimpleInstance referenceTherapeutic, Map<String, SimpleInstance> chEBIDatabaseIdentifiers) throws IOException {

        logger.info("Removing " + formatChEBIIdentifiers(chEBIDatabaseIdentifiers.keySet()) +
            " cross-references from " + referenceTherapeutic);

        for (SimpleInstance chEBIDatabaseIdentifier : chEBIDatabaseIdentifiers.values()) {
            List<SimpleInstance> crossReferences =
                (List<SimpleInstance>) referenceTherapeutic.getAttribute(ReactomeJavaConstants.crossReference);
            crossReferences.removeIf(crossReference -> crossReference.getDbId().equals(chEBIDatabaseIdentifier.getDbId()));
            referenceTherapeutic.setAttribute(ReactomeJavaConstants.crossReference, crossReferences);
        }

        report(referenceTherapeutic, chEBIDatabaseIdentifiers.keySet(), "removal");
    }

    private String formatChEBIIdentifiers(Set<String> chEBIIdentifiers) {
        return chEBIIdentifiers.stream().map(id -> "ChEBI:" + id).collect(Collectors.joining(", "));
    }

    private SimpleInstance createDatabaseIdentifier(String chEBIId) {
        SimpleInstance databaseIdentifier = new SimpleInstance();

        databaseIdentifier.setSchemaClassName(ReactomeJavaConstants.DatabaseIdentifier);
        databaseIdentifier.setDefaultPersonId(getPersonDbId());
        databaseIdentifier.setAttribute(ReactomeJavaConstants.referenceDatabase, getChEBIReferenceDatabase());
        databaseIdentifier.setAttribute(ReactomeJavaConstants.identifier, chEBIId);
        databaseIdentifier.setDisplayName(generateDisplayName(databaseIdentifier));

        return databaseIdentifier;
    }

    private void updateInDb(SimpleInstance instance) {
        instance.setDefaultPersonId(getPersonDbId());
        curatorToolAPI.commit(instance);
    }

    private SimpleInstance getChEBIReferenceDatabase() {
        return curatorToolAPI.fetchChEBIReferenceDatabase();
    }

    private String generateDisplayName(SimpleInstance databaseIdentifier) {
        SimpleInstance referenceDatabase =
            (SimpleInstance) databaseIdentifier.getAttribute(ReactomeJavaConstants.referenceDatabase);
        String referenceDatabaseName = referenceDatabase.getDisplayName();
        String identifier = (String) databaseIdentifier.getAttribute(ReactomeJavaConstants.identifier);

        return referenceDatabaseName + ":" + identifier;
    }

    private long getPersonDbId() {
        try {
            return Long.parseLong(getConfigProperties().getProperty("personId"));
        } catch (IOException e) {
            throw new RuntimeException("Unable to get personId", e);
        }
    }

    private Properties getConfigProperties() throws IOException {
        Properties props = new Properties();
        props.load(Files.newInputStream(Path.of(this.configFilePath)));
        return props;
    }

    private Map<String, Set<String>> getGuideToPharmacology2ChEBIMapping() throws IOException {
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

    private Map<String, String> processGuideToPharmacologyFile() {
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

    private Map<String, Set<String>> processPubChemFile() throws IOException {
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

    private String extractChEBIId(String chEBIIdColumn) {
        Pattern chEBIIdPattern = Pattern.compile("CHEBI:(\\d+).*");
        Matcher chEBIIdMatcher = chEBIIdPattern.matcher(chEBIIdColumn);

        if (!chEBIIdMatcher.find()) {
            return "";
        }
        return chEBIIdMatcher.group(1);
    }

    private CSVParser getGtoPCSVParser(BufferedReader csvFileBufferedReader) throws IOException {
        return new CSVParser(
            getReaderAfterVersionHeader(csvFileBufferedReader),
            CSVFormat.DEFAULT.withFirstRecordAsHeader()
        );
    }

    private Reader getReaderAfterVersionHeader(BufferedReader reader) throws IOException {
        String header = reader.readLine();
        if (!header.contains("GtoPdb Version")) {
            reader.reset(); // Reset to beginning to include first line if not version header
        }
        return reader; // Return reader at proper position after version header, if exists
    }

    private void report(SimpleInstance referenceTherapeutic, Set<String> chEBIIds, String typeOfChange) throws IOException {
        String outputLine = String.join("\t",
                referenceTherapeutic.getDbId().toString(),
                referenceTherapeutic.getDisplayName(),
                "ChEBI Id(s):" + chEBIIds,
                typeOfChange
            ).concat(System.lineSeparator());

        Files.write(
            getOutputFilePath(),
            outputLine.getBytes(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        );
    }

    private Path getOutputFilePath() {
        return Path.of(this.outputFilePath);
    }

    private BufferedReader getGuideToPharmacologyFileBufferedReader() throws IOException {
        return getBufferedReader("ligand_id_mapping.csv");
    }

    private BufferedReader getPubChemCompoundFileBufferedReader() throws IOException {
        return getBufferedReader("pubchem_id_mapping.tsv");
    }

    private BufferedReader getBufferedReader(String fileName) throws IOException {
        return Files.newBufferedReader(Paths.get(fileName));
    }
}