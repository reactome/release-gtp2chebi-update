package org.reactome;

import org.reactome.curation.CuratorToolWsApplication;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.NamedReferrerList;
import org.reactome.curation.model.SimpleInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.neo4j.driver.Driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Joel Weiser (joel.weiser@oicr.on.ca)
 * Created 7/13/2026
 */
public class CuratorToolAPI {

	private static final Logger logger = LoggerFactory.getLogger(CuratorToolAPI.class);
	private static CurationController controller;

	private ConfigurableApplicationContext applicationContext;
	// Non-null only if a Neo4jTransactionManager is available; used to batch many commits into one
	// Neo4j transaction (fewer begin/commit round-trips). Null => commits run individually as before.
	private TransactionTemplate transactionTemplate;

	public CuratorToolAPI() {
		if (controller == null) {
			controller = this.initController();
			if (controller == null) {
				throw new IllegalStateException("Failed to initialize CuratorToolAPI: controller is null");
			}
		}
	}

	public SimpleInstance commit(SimpleInstance simpleInstance) {
		return controller.commit(simpleInstance);
	}

	/**
	 * Run the given work inside a single Neo4j transaction so the many commits it performs are flushed
	 * together instead of each opening its own transaction. If no Neo4jTransactionManager is available the
	 * work simply runs as-is (unchanged, per-commit behavior). A RuntimeException from the work rolls the
	 * transaction back and propagates.
	 */
	public void runInTransaction(Runnable work) {
		if (transactionTemplate == null) {
			work.run();
			return;
		}
		transactionTemplate.executeWithoutResult(status -> work.run());
	}

	public SimpleInstance fetchChEBIReferenceDatabase() {
		return controller.findByDisplayName("ChEBI", "ReferenceDatabase");
	}

	public List<SimpleInstance> fetchAllReferenceTherapeutics() {
		List<SimpleInstance> referenceTherapeutics = new ArrayList<>();
		int skip = 0;
		int pageSize = 500;
		Integer totalCount;

		do {
			InstanceList page = controller.listInstances(
				"ReferenceTherapeutic", skip, pageSize, Optional.empty());
			if (page == null || page.isEmpty())
				break;
			referenceTherapeutics.addAll(page.getInstances());
			totalCount = page.getTotalCount();
			skip += pageSize;
		} while (totalCount != null && referenceTherapeutics.size() < totalCount);

		return referenceTherapeutics.stream().map(this::inflate).collect(Collectors.toList());
	}

	public void close() {
		applicationContext.close();
	}

	public SimpleInstance inflate(SimpleInstance shellInstance) {
		return controller.findByDdIdInInstance(shellInstance.getDbId());
	}

	public List<SimpleInstance> getReferrers(SimpleInstance instance, String referrerAttributeName) throws Exception {
		return controller.getReferrers(instance.getDbId())
			.stream()
			.filter(g -> referrerAttributeName.equals(g.getAttributeName()))
			.findFirst()
			.map(NamedReferrerList::getReferrers)
			.orElse(Collections.emptyList());
	}



	// The following code is copied directly from the slicing tool project.
	private CurationController initController() {
		try {
			// curator-tool-ws's bundled application.properties forces DEBUG for these loggers.
			// System properties outrank a classpath application.properties in Spring Boot's
			// precedence order, so this quiets them for the batch run without editing
			// curator-tool-ws. (SpringApplicationBuilder.properties(...) are default/lowest
			// precedence and would NOT override application.properties.)
			System.setProperty("logging.level.org.springframework.data.neo4j", "WARN");
			System.setProperty("logging.level.org.springframework.security", "WARN");

			applicationContext = new SpringApplicationBuilder(CuratorToolWsApplication.class)
				.web(WebApplicationType.SERVLET)
				.properties("server.port=-1")  // disable HTTP server; keep full servlet context for correct AspectJ wiring
				.run();
			this.transactionTemplate = buildTransactionTemplate(applicationContext);
			return applicationContext.getBean(CurationController.class);
		}
		catch (Exception e) {
			logger.error("GraphDBInstanceManager.initController(): " + e.getMessage(), e);
		}
		return null;
	}

	// Batching only helps if the underlying neo4jClient operations join a single Neo4j transaction, which
	// requires a Neo4jTransactionManager bean. curator-tool-ws also configures JPA (H2 users), so that bean
	// is not guaranteed to exist; if it is absent we degrade to per-commit transactions rather than fail.
	private static TransactionTemplate buildTransactionTemplate(ConfigurableApplicationContext context) {
		// Prefer an existing Neo4jTransactionManager bean.
		try {
			Neo4jTransactionManager txManager = context.getBean(Neo4jTransactionManager.class);
			logger.info("Transaction batching ENABLED (existing Neo4jTransactionManager bean).");
			return new TransactionTemplate(txManager);
		}
		catch (Exception noBean) {
			// curator-tool-ws also configures JPA (H2 users), which can suppress the auto-configured
			// Neo4jTransactionManager bean. Build one over the SAME Driver + DatabaseSelectionProvider the
			// Neo4jClient uses, so the client's operations join our transaction (they are keyed by driver +
			// database in TransactionSynchronizationManager).
			try {
				Driver driver = context.getBean(Driver.class);
				DatabaseSelectionProvider databaseSelectionProvider = context.getBean(DatabaseSelectionProvider.class);
				logger.info("Transaction batching ENABLED (Neo4jTransactionManager built over the shared Driver).");
				return new TransactionTemplate(new Neo4jTransactionManager(driver, databaseSelectionProvider));
			}
			catch (Exception noDriver) {
				logger.warn("Transaction batching DISABLED: could not obtain a Neo4jTransactionManager or a "
					+ "Driver/DatabaseSelectionProvider; commits run individually. Reason: " + noDriver.getMessage());
				return null;
			}
		}
	}
}
