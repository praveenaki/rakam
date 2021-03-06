package org.rakam;

import com.google.common.eventbus.EventBus;
import org.rakam.analysis.ContinuousQueryService;
import org.rakam.analysis.InMemoryQueryMetadataStore;
import org.rakam.analysis.TestContinuousQueryService;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.collection.FieldDependencyBuilder;
import org.rakam.event.TestingEnvironment;
import org.rakam.presto.analysis.PrestoContinuousQueryService;
import org.rakam.presto.analysis.PrestoMetastore;
import org.rakam.presto.analysis.PrestoQueryExecutor;
import org.testng.annotations.BeforeSuite;

public class TestPrestoContinuousQueryService extends TestContinuousQueryService {
    private PrestoContinuousQueryService continuousQueryService;
    private Metastore metastore;
    private TestingEnvironment testEnvironment;

    @BeforeSuite
    public void setUp() throws Exception {
        testEnvironment = new TestingEnvironment();

        metastore = new PrestoMetastore(testEnvironment.getPrestoMetastore(),
                new EventBus(), new FieldDependencyBuilder().build(), testEnvironment.getPrestoConfig());
        metastore.setup();

        InMemoryQueryMetadataStore queryMetadataStore = new InMemoryQueryMetadataStore();

        PrestoQueryExecutor prestoQueryExecutor = new PrestoQueryExecutor(testEnvironment.getPrestoConfig(), metastore);

        continuousQueryService = new PrestoContinuousQueryService(queryMetadataStore,
                prestoQueryExecutor, testEnvironment.getPrestoConfig());
    }

    @Override
    public ContinuousQueryService getContinuousQueryService() {
        return continuousQueryService;
    }

    @Override
    public Metastore getMetastore() {
        return metastore;
    }
}
