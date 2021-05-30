package org.opensearch.security;

import org.junit.Assert;
import org.junit.Test;
import org.opensearch.OpenSearchSecurityException;
import org.opensearch.action.admin.cluster.health.ClusterHealthRequest;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.opensearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.node.Node;
import org.opensearch.node.PluginAwareNode;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.security.support.ConfigConstants;
import org.opensearch.security.test.DynamicSecurityConfig;
import org.opensearch.security.test.SingleClusterTest;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.Netty4Plugin;
import org.opensearch.watcher.ResourceWatcherService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;

public class AssumeRolesIntegTest extends SingleClusterTest {

    public static class AssumeRolesPlugin extends Plugin implements ActionPlugin {
        Settings settings;
        public static String assumeRoles = null;

        public AssumeRolesPlugin(final Settings settings, final Path configPath) {
            this.settings = settings;
        }

        @Override
        public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
                                                   ResourceWatcherService resourceWatcherService, ScriptService scriptService,
                                                   NamedXContentRegistry xContentRegistry, Environment environment,
                                                   NodeEnvironment nodeEnvironment, NamedWriteableRegistry namedWriteableRegistry,
                                                   IndexNameExpressionResolver indexNameExpressionResolver,
                                                   Supplier<RepositoriesService> repositoriesServiceSupplier) {
            if(assumeRoles != null)
                threadPool.getThreadContext().putTransient(ConfigConstants.OPENDISTRO_SECURITY_ASSUME_ROLES, assumeRoles);
            return new ArrayList<>();
        }
    }

    //Wait for the security plugin to load roles.
    private void waitForInit(Client client) throws Exception {
        try {
            client.admin().cluster().health(new ClusterHealthRequest()).actionGet();
        } catch (OpenSearchSecurityException ex) {
            if(ex.getMessage().contains("OpenSearch Security not initialized")) {
                Thread.sleep(500);
                waitForInit(client);
            }
        }
    }

    @Test
    public void testAssumeRoles() throws Exception {
        setup(Settings.EMPTY, new DynamicSecurityConfig().setSecurityRoles("roles.yml"), Settings.EMPTY);

        final Settings tcSettings = Settings.builder()
                .put(minimumSecuritySettings(Settings.EMPTY).get(0))
                .put("cluster.name", clusterInfo.clustername)
                .put("node.data", false)
                .put("node.master", false)
                .put("node.ingest", false)
                .put("path.data", "./target/data/" + clusterInfo.clustername + "/cert/data")
                .put("path.logs", "./target/data/" + clusterInfo.clustername + "/cert/logs")
                .put("path.home", "./target")
                .put("node.name", "testclient")
                .put("discovery.initial_state_timeout", "8s")
                .put("plugins.security.allow_default_init_securityindex", "true")
                .putList("discovery.zen.ping.unicast.hosts", clusterInfo.nodeHost + ":" + clusterInfo.nodePort)
                .build();

        // 1. Without assume roles
        try (Node node = new PluginAwareNode(false, tcSettings, Netty4Plugin.class,
                OpenSearchSecurityPlugin.class, AssumeRolesPlugin.class).start()) {
            waitForInit(node.client());
            CreateIndexResponse cir = node.client().admin().indices().create(new CreateIndexRequest("captain-logs-1")).actionGet();
            Assert.assertTrue(cir.isAcknowledged());
            IndicesExistsResponse ier = node.client().admin().indices().exists(new IndicesExistsRequest("captain-logs-1")).actionGet();
            Assert.assertTrue(ier.isExists());
        }

        // 2. with assume roles invalid to the user
        AssumeRolesPlugin.assumeRoles = "invalid_role";
        try (Node node = new PluginAwareNode(false, tcSettings, Netty4Plugin.class,
                OpenSearchSecurityPlugin.class, AssumeRolesPlugin.class).start()) {
            waitForInit(node.client());
            CreateIndexResponse cir = node.client().admin().indices().create(new CreateIndexRequest("captain-logs-2")).actionGet();
        } catch (OpenSearchSecurityException ex) {
            Assert.assertNotNull(ex);
            Assert.assertTrue(ex.getMessage().contains("assume roles"));
        }

        // 3. with assume roles invalid to the user
        AssumeRolesPlugin.assumeRoles = "opendistro_security_all_access";
        try (Node node = new PluginAwareNode(false, tcSettings, Netty4Plugin.class,
                OpenSearchSecurityPlugin.class, AssumeRolesPlugin.class).start()) {
            waitForInit(node.client());
            CreateIndexResponse cir = node.client().admin().indices().create(new CreateIndexRequest("captain-logs-3")).actionGet();
            Assert.assertTrue(cir.isAcknowledged());
        }
    }
}
