package org.reactome;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

/**
 * @author Joel Weiser (joel.weiser@oicr.on.ca)
 * Created 10/31/2025
 */
public class FileDownloader {
	private static final Logger logger = LogManager.getLogger();

	public void downloadFiles() throws IOException, InterruptedException {
		downloadLigandIdMappingFileIfNeeded();
		downloadPubChemCompoundMappingFileIfNeeded();
	}

	public void downloadLigandIdMappingFileIfNeeded() throws IOException, InterruptedException {
		final String ligandIdMappingURL = "https://www.guidetopharmacology.org/DATA/ligands.csv";
		final String ligandIdMappingFileName = "ligand_id_mapping.csv";

		Path savePath = Path.of(ligandIdMappingFileName);

		if (Files.exists(savePath) && !isOlderThan2Days(savePath)) {
			return;
		}

		logger.info("Downloading ligand id mapping file...");

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(ligandIdMappingURL))
			.build();

		client.send(request, HttpResponse.BodyHandlers.ofFile(savePath));

		logger.info("Finished downloading ligand id mapping file");
	}

	public void downloadPubChemCompoundMappingFileIfNeeded() throws IOException, InterruptedException {
		final String pubChemCompoundFileName = "pubchem_id_mapping.tsv";
		final String pubChemCompoundURL = "https://ftp.ncbi.nlm.nih.gov/pubchem/Compound/Extras/CID-Synonym-filtered.gz";

		if (Files.exists(Path.of(pubChemCompoundFileName)) && !isOlderThan2Days(Path.of(pubChemCompoundFileName))) {
			return;
		}

		logger.info("Downloading PubChem Compound mapping file...");

		try (InputStream in = new GZIPInputStream(new URL(pubChemCompoundURL).openStream());
			 BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			 BufferedWriter writer = new BufferedWriter(new FileWriter(pubChemCompoundFileName))) {

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains("CHEBI:")) {
					writer.write(line);
					writer.newLine();
				}
			}
		}

		logger.info("Finished downloading PubChem Compound mapping file");
	}

	private boolean isOlderThan2Days(Path filePath) throws IOException {
		final int maxAgeInDays = 2;

		FileTime lastModified = Files.getLastModifiedTime(filePath);
		Instant fileInstant = lastModified.toInstant();
		Instant cutoff = Instant.now().minus(Duration.ofDays(maxAgeInDays));

		return fileInstant.isBefore(cutoff);
	}
}
