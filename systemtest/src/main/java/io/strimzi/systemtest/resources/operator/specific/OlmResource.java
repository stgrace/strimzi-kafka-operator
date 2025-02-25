/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.resources.operator.specific;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.enums.OlmInstallationStrategy;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.specific.OlmUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.executor.Exec;
import io.strimzi.test.k8s.KubeClusterResource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.stream.Collectors;

import static io.strimzi.systemtest.resources.ResourceManager.CR_CREATION_TIMEOUT;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class OlmResource implements SpecificResourceType {
    private static final Logger LOGGER = LogManager.getLogger(OlmResource.class);

    public static final String NO_MORE_NON_USED_INSTALL_PLANS = "NoMoreNonUsedInstallPlans";

    // only three versions
    private static final Map<String, Boolean> CLOSED_MAP_INSTALL_PLAN = new HashMap<>(3);

    private static Map<String, JsonObject> exampleResources = new HashMap<>();

    private String deploymentName;
    private String namespace;
    private String csvName;

    @Override
    public void create(ExtensionContext extensionContext) {
        this.create(extensionContext, Constants.CO_OPERATION_TIMEOUT_DEFAULT, Constants.RECONCILIATION_INTERVAL, null);
    }

    public void create(ExtensionContext extensionContext, long operationTimeout, long reconciliationInterval, List<EnvVar> extraEnvVars) {
        ResourceManager.STORED_RESOURCES.computeIfAbsent(extensionContext.getDisplayName(), k -> new Stack<>());
        this.clusterOperator(this.namespace, operationTimeout, reconciliationInterval, extraEnvVars);
    }

    public void create(final String namespaceName, OlmInstallationStrategy olmInstallationStrategy, String fromVersion,
                       final String channelName, List<EnvVar> extraEnvVars) {
        this.namespace = namespaceName;
        this.clusterOperator(namespaceName, olmInstallationStrategy, fromVersion, channelName, extraEnvVars);
    }

    @Override
    public void delete() {
        deleteOlm(deploymentName, namespace, csvName);
    }

    public OlmResource(String namespace) {
        this.namespace = namespace;
    }

    private void clusterOperator(String namespace, OlmInstallationStrategy olmInstallationStrategy, String fromVersion,
                                 final String channelName, List<EnvVar> extraEnvVars) {
        clusterOperator(namespace, Constants.CO_OPERATION_TIMEOUT_DEFAULT, Constants.RECONCILIATION_INTERVAL,
            olmInstallationStrategy, fromVersion, channelName, extraEnvVars);
    }

    private void clusterOperator(String namespace, long operationTimeout, long reconciliationInterval, List<EnvVar> extraEnvVars) {
        clusterOperator(namespace, operationTimeout, reconciliationInterval, OlmInstallationStrategy.Automatic, null,
            "stable", extraEnvVars);
    }

    private void clusterOperator(String namespace, long operationTimeout, long reconciliationInterval,
                                 OlmInstallationStrategy olmInstallationStrategy, String fromVersion,
                                 final String channelName, List<EnvVar> extraEnvVars) {

        // if on cluster is not defaultOlmNamespace apply 'operator group' in current namespace
        if (!KubeClusterResource.getInstance().getDefaultOlmNamespace().equals(namespace)) {
            createOperatorGroup(namespace);
        }

        if (fromVersion != null) {
            createAndModifySubscription(namespace, operationTimeout, reconciliationInterval, olmInstallationStrategy,
                fromVersion, channelName, extraEnvVars);
            // must be strimzi-cluster-operator.v0.18.0
            csvName = Environment.OLM_APP_BUNDLE_PREFIX + ".v" + fromVersion;
        } else {
            createAndModifySubscriptionLatestRelease(namespace, operationTimeout, reconciliationInterval,
                olmInstallationStrategy, extraEnvVars);
            csvName = Environment.OLM_APP_BUNDLE_PREFIX + ".v" + Environment.OLM_OPERATOR_LATEST_RELEASE_VERSION;
        }

        // manual installation needs approval with patch
        if (olmInstallationStrategy == OlmInstallationStrategy.Manual) {
            OlmUtils.waitUntilNonUsedInstallPlanIsPresent(fromVersion);
            obtainInstallPlanName();
            approveNonUsedInstallPlan();
        }

        // Make sure that operator will be created
        TestUtils.waitFor("Cluster Operator deployment creation", Constants.GLOBAL_POLL_INTERVAL, CR_CREATION_TIMEOUT,
            () -> kubeClient(namespace).getDeploymentNameByPrefix(Environment.OLM_OPERATOR_DEPLOYMENT_NAME) != null);

        deploymentName = kubeClient(namespace).getDeploymentNameByPrefix(Environment.OLM_OPERATOR_DEPLOYMENT_NAME);
        ResourceManager.setCoDeploymentName(deploymentName);

        // Wait for operator creation
        waitFor(deploymentName, namespace, 1);

        exampleResources = parseExamplesFromCsv(csvName, namespace);
    }

    /**
     * Get install plan name and store it to closedMapInstallPlan
     */
    public static void obtainInstallPlanName() {
        String installPlansPureString = cmdKubeClient().execInCurrentNamespace("get", "installplan").out();
        String[] installPlansLines = installPlansPureString.split("\n");

        for (String line : installPlansLines) {
            // line NAME  CSV  APPROVAL   APPROVED
            String[] wholeLine = line.split(" ");

            // name
            if (wholeLine[0].startsWith("install-")) {

                // if is not already applied add to closed map
                if (!CLOSED_MAP_INSTALL_PLAN.containsKey(wholeLine[0])) {
                    LOGGER.info("CLOSED_MAP_INSTALL_PLAN does not contain {} install plan so this is not used and will " +
                        "be in the following upgrade.", wholeLine[0]);
                    CLOSED_MAP_INSTALL_PLAN.put(wholeLine[0], Boolean.FALSE);
                }
            }
        }
        if (!(CLOSED_MAP_INSTALL_PLAN.keySet().size() > 0)) {
            throw new RuntimeException("No install plans located in namespace:" + cmdKubeClient().namespace());
        }
    }

    /**
     * Get specific version of cluster operator with prefix name in format: 'strimzi-cluster-operator.v0.18.0'
     * @return version with prefix name
     */
    public static String getClusterOperatorVersion() {
        String installPlansPureString = cmdKubeClient().execInCurrentNamespace("get", "installplan").out();
        String[] installPlansLines = installPlansPureString.split("\n");

        for (String line : installPlansLines) {
            // line = NAME   CSV   APPROVAL   APPROVED
            String[] wholeLine = line.split("   ");

            // non-used install plan
            if (wholeLine[0].equals(getNonUsedInstallPlan())) {
                return wholeLine[1];
            }
        }
        throw new RuntimeException("Version was not found in the install plan.");
    }

    public static String getNonUsedInstallPlan() {
        String[] nonUsedInstallPlan = new String[1];

        for (Map.Entry<String, Boolean> entry : CLOSED_MAP_INSTALL_PLAN.entrySet()) {
            // if value is FALSE we are gonna use it = non-used install plan
            if (!entry.getValue()) {
                nonUsedInstallPlan[0] = entry.getKey();
                break;
            }
            nonUsedInstallPlan[0] = NO_MORE_NON_USED_INSTALL_PLANS;
        }

        LOGGER.info("Non-used install plan is {}", nonUsedInstallPlan[0]);
        return nonUsedInstallPlan[0];
    }

    /**
     * Patches specific non used install plan, which will approve installation. Only for manual installation strategy.
     * Also updates closedMapInstallPlan map and set specific install plan to true.
     */
    private static void approveNonUsedInstallPlan() {
        String nonUsedInstallPlan = getNonUsedInstallPlan();

        try {
            LOGGER.info("Approving {} install plan", nonUsedInstallPlan);
            String dynamicScriptContent =
                "#!/bin/bash\n" +
                    cmdKubeClient().cmd() +
                    " patch installplan " + nonUsedInstallPlan + " --type json  --patch '[{\"op\": \"add\", \"path\": \"/spec/approved\", \"value\": true}]' -n " + KubeClusterResource.getInstance().getNamespace();

            InputStream inputStream = new ByteArrayInputStream(dynamicScriptContent.getBytes(Charset.defaultCharset()));
            File patchScript = File.createTempFile("installplan_patch",  ".sh");
            Files.copy(inputStream, patchScript.toPath(), StandardCopyOption.REPLACE_EXISTING);

            Exec.exec("bash", patchScript.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Upgrade cluster operator by obtaining new install plan, which was not used and also approves installation by
     * changing the install plan YAML
     */
    public void upgradeClusterOperator() {
        if (kubeClient().listPodsByPrefixInName(ResourceManager.getCoDeploymentName()).size() == 0) {
            throw new RuntimeException("We can not perform upgrade! Cluster operator pod is not present.");
        }

        obtainInstallPlanName();
        approveNonUsedInstallPlan();
    }

    public void updateSubscription(final String newChannelName, final String olmOperatorVersion, final long operationsTimeout,
                                   final long reconciliationInterval, final OlmInstallationStrategy olmInstallationStrategy) {
        createAndModifySubscription(this.namespace, operationsTimeout, reconciliationInterval, olmInstallationStrategy,
            olmOperatorVersion, newChannelName, null);
        this.csvName = Environment.OLM_APP_BUNDLE_PREFIX + ".v" + olmOperatorVersion;
    }

    /**
     * Creates OperatorGroup from `olm/operator-group.yaml` and modify "${OPERATOR_NAMESPACE}" attribute in YAML
     * @param namespace namespace where you want to apply OperatorGroup  kind
     */
    private static void createOperatorGroup(String namespace) {
        try {
            File operatorGroupFile = File.createTempFile("operatorgroup", ".yaml");
            InputStream groupInputStream = OlmResource.class.getClassLoader().getResourceAsStream("olm/operator-group.yaml");
            String operatorGroup = TestUtils.readResource(groupInputStream);
            TestUtils.writeFile(operatorGroupFile.getAbsolutePath(), operatorGroup.replace("${OPERATOR_NAMESPACE}", namespace));
            ResourceManager.cmdKubeClient().apply(operatorGroupFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates Subscription from "olm/subscription.yaml" and modify "${OPERATOR_NAMESPACE}", "${OLM_OPERATOR_NAME}...
     * attributes.
     * @param namespace namespace where you want to apply Subscription kind
     * @param reconciliationInterval reconciliation interval of cluster operator
     * @param operationTimeout operation timeout  of cluster operator
     * @param installationStrategy type of installation
     */
    private static void createAndModifySubscription(String namespace, long operationTimeout, long reconciliationInterval,
                                                    OlmInstallationStrategy installationStrategy, String version,
                                                    final String channelName, List<EnvVar> extraEnvVars) {
        try {
            YAMLMapper mapper = new YAMLMapper();
            File loadedSubscriptionFile = new File(Objects.requireNonNull(OlmResource.class.getClassLoader().getResource("olm/subscription.yaml")).getPath());
            JsonNode node = mapper.readTree(loadedSubscriptionFile);

            ObjectNode metadataNode = (ObjectNode) node.at("/metadata");
            metadataNode.put("namespace", namespace);

            ObjectNode specNode = (ObjectNode) node.at("/spec");
            specNode.put("name", Environment.OLM_OPERATOR_NAME);
            specNode.put("source", Environment.OLM_SOURCE_NAME);
            specNode.put("sourceNamespace", Environment.OLM_SOURCE_NAMESPACE);
            specNode.put("startingCSV", Environment.OLM_APP_BUNDLE_PREFIX + ".v0.29.0" + version);
            specNode.put("channel", channelName);
            specNode.put("installPlanApproval", installationStrategy.toString());

            ObjectNode configNode = (ObjectNode) specNode.at("/config");
            for (JsonNode envVar : configNode.get("env")) {
                String varName = envVar.get("name").textValue();
                if (varName.matches("STRIMZI_FULL_RECONCILIATION_INTERVAL_MS")) {
                    ((ObjectNode) envVar).put("value", String.valueOf(reconciliationInterval));
                }
                if (varName.matches("STRIMZI_OPERATION_TIMEOUT_MS")) {
                    ((ObjectNode) envVar).put("value", String.valueOf(operationTimeout));
                }
                if (varName.matches("STRIMZI_FEATURE_GATES")) {
                    ((ObjectNode) envVar).put("value", Environment.STRIMZI_FEATURE_GATES);
                }
            }

            if (extraEnvVars != null) {
                JsonNode envNode = configNode.at("/env");
                for (EnvVar envVar: extraEnvVars) {
                    ObjectNode objectEnvVar = mapper.createObjectNode();
                    objectEnvVar.put("name", envVar.getName());
                    objectEnvVar.put("value", envVar.getValue());

                    ((ArrayNode) envNode).add(objectEnvVar);
                }
            }

            cmdKubeClient(namespace).applyContent(mapper.writeValueAsString(node));
        }  catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void createAndModifySubscriptionLatestRelease(String namespace, long reconciliationInterval,
                                                                 long operationTimeout, OlmInstallationStrategy installationStrategy, List<EnvVar> extraEnvVars) {
        createAndModifySubscription(namespace, reconciliationInterval, operationTimeout, installationStrategy,
            Environment.OLM_OPERATOR_LATEST_RELEASE_VERSION, "stable", extraEnvVars);
    }

    private static void deleteOlm(String deploymentName, String namespace, String csvName) {
        if (ResourceManager.kubeClient().getDeploymentNameByPrefix(Environment.OLM_OPERATOR_DEPLOYMENT_NAME) != null) {
            DeploymentUtils.waitForDeploymentDeletion(namespace, deploymentName);
        } else {
            LOGGER.info("Cluster Operator deployment: {} is already deleted in namespace: {}", deploymentName, namespace);
        }
        LOGGER.info("Deleting Subscription, OperatorGroups and ClusterServiceVersion from namespace: {}", namespace);
        ResourceManager.cmdKubeClient().exec(false, "delete", "subscriptions", "-l", "app=strimzi", "-n", namespace);
        ResourceManager.cmdKubeClient().exec(false, "delete", "operatorgroups", "-l", "app=strimzi", "-n", namespace);
        ResourceManager.cmdKubeClient().exec(false, "delete", "csv", csvName, "-n", namespace);
    }

    private static void waitFor(String deploymentName, String namespace, int replicas) {
        LOGGER.info("Waiting for deployment {} in namespace {}", deploymentName, namespace);
        DeploymentUtils.waitForDeploymentAndPodsReady(namespace, deploymentName, replicas);
        LOGGER.info("Deployment {} in namespace {} is ready", deploymentName, namespace);
    }

    private static Map<String, JsonObject> parseExamplesFromCsv(String csvName, String namespace) {
        String csvString = ResourceManager.cmdKubeClient().exec(true, Level.DEBUG, "get", "csv", csvName, "-o", "json", "-n", namespace).out();
        JsonObject csv = new JsonObject(csvString);
        String almExamples = csv.getJsonObject("metadata").getJsonObject("annotations").getString("alm-examples");
        JsonArray examples = new JsonArray(almExamples);
        return examples.stream().map(o -> (JsonObject) o).collect(Collectors.toMap(object -> object.getString("kind"), object -> object));
    }

    public static Map<String, JsonObject> getExampleResources() {
        return exampleResources;
    }

    public static void setExampleResources(Map<String, JsonObject> exampleResources) {
        OlmResource.exampleResources = exampleResources;
    }

    public static Map<String, Boolean> getClosedMapInstallPlan() {
        return CLOSED_MAP_INSTALL_PLAN;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }
}
