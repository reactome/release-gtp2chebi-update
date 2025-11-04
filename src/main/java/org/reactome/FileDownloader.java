package org.reactome;

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

	public void downloadFiles() throws IOException, InterruptedException {
		downloadLigandIdMappingFile();
		downloadPubChemCompoundMappingFile();
	}

	public void downloadLigandIdMappingFile() throws IOException, InterruptedException {
		final String ligandIdMappingURL = "https://www.guidetopharmacology.org/DATA/ligands.csv";
		final String ligandIdMappingFileName = "ligand_id_mapping.csv";

		Path savePath = Path.of(ligandIdMappingFileName);

		if (Files.exists(savePath) && !isOlderThan2Days(savePath)) {
			return;
		}

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(ligandIdMappingURL))
			.build();

		client.send(request, HttpResponse.BodyHandlers.ofFile(savePath));
	}

	public void downloadPubChemCompoundMappingFile() throws IOException, InterruptedException {
		final String pubChemCompoundFileName = "pubchem_id_mapping.tsv";
		final String pubChemCompoundURL = "https://ftp.ncbi.nlm.nih.gov/pubchem/Compound/Extras/CID-Synonym-filtered.gz";

		if (Files.exists(Path.of(pubChemCompoundFileName)) && !isOlderThan2Days(Path.of(pubChemCompoundFileName))) {
			return;
		}

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
	}

	private boolean isOlderThan2Days(Path filePath) throws IOException {
		final int maxAgeInDays = 2;

		FileTime lastModified = Files.getLastModifiedTime(filePath);
		Instant fileInstant = lastModified.toInstant();
		Instant cutoff = Instant.now().minus(Duration.ofDays(maxAgeInDays));

		return fileInstant.isBefore(cutoff);
	}
}
