package com.mesosphere.sdk.scheduler.uninstall;

import com.mesosphere.sdk.dcos.SecretsClient;
import com.mesosphere.sdk.scheduler.plan.Plan;
import com.mesosphere.sdk.scheduler.plan.Status;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.storage.MemPersister;
import com.mesosphere.sdk.testutils.*;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Mockito.*;

public class UninstallSchedulerTest extends DefaultCapabilitiesTestSuite {

    private static final String RESERVED_RESOURCE_1_ID = "reserved-resource-id";
    private static final String RESERVED_RESOURCE_2_ID = "reserved-volume-id";
    private static final String RESERVED_RESOURCE_3_ID = "reserved-cpu-id-0";
    private static final String RESERVED_RESOURCE_4_ID = "reserved-cpu-id-1";

    private static final Protos.Resource RESERVED_RESOURCE_1 = ResourceTestUtils.getExpectedRanges(
            "ports",
            Collections.singletonList(Protos.Value.Range.newBuilder().setBegin(123).setEnd(234).build()),
            RESERVED_RESOURCE_1_ID,
            TestConstants.ROLE,
            TestConstants.PRINCIPAL);
    private static final Protos.Resource RESERVED_RESOURCE_2 = ResourceTestUtils.getExpectedRootVolume(
            999.0,
            RESERVED_RESOURCE_2_ID,
            TestConstants.CONTAINER_PATH,
            TestConstants.ROLE,
            TestConstants.PRINCIPAL,
            RESERVED_RESOURCE_2_ID);
    private static final Protos.Resource RESERVED_RESOURCE_3 = ResourceTestUtils.getExpectedScalar(
            "cpus",
            1.0,
            RESERVED_RESOURCE_3_ID,
            TestConstants.ROLE,
            TestConstants.PRINCIPAL);
    private static final Protos.Resource RESERVED_RESOURCE_4 = ResourceTestUtils.getExpectedScalar(
            "cpus",
            1.0,
            RESERVED_RESOURCE_4_ID,
            TestConstants.ROLE,
            TestConstants.PRINCIPAL);

    private static final Protos.TaskInfo TASK_A =
            TaskTestUtils.getTaskInfo(Arrays.asList(RESERVED_RESOURCE_1, RESERVED_RESOURCE_2, RESERVED_RESOURCE_3));
    private static final Protos.TaskInfo TASK_B = Protos.TaskInfo.newBuilder(
            TaskTestUtils.getTaskInfo(Arrays.asList(RESERVED_RESOURCE_2, RESERVED_RESOURCE_4)))
                    .setName("other-task-info")
                    .build();

    private StateStore stateStore;
    private UninstallScheduler uninstallScheduler;

    @Mock private ConfigStore<ServiceSpec> mockConfigStore;
    @Mock private SchedulerDriver mockSchedulerDriver;
    @Mock private SecretsClient mockSecretsClient;

    @Before
    public void beforeEach() throws Exception {
        MockitoAnnotations.initMocks(this);
        stateStore = new StateStore(new MemPersister());
        stateStore.storeTasks(Collections.singletonList(TASK_A));
        stateStore.storeFrameworkId(TestConstants.FRAMEWORK_ID);
        uninstallScheduler = new TestScheduler(TestConstants.SERVICE_NAME, stateStore, mockConfigStore, true);
        uninstallScheduler.registered(mockSchedulerDriver, TestConstants.FRAMEWORK_ID, TestConstants.MASTER_INFO);
    }

    @Test
    public void testEmptyOffers() throws Exception {
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Collections.emptyList());
        verify(mockSchedulerDriver, times(1)).reconcileTasks(any());
        verify(mockSchedulerDriver, times(0)).acceptOffers(any(), anyCollectionOf(Protos.Offer.Operation.class), any());
        verify(mockSchedulerDriver, times(0)).declineOffer(any(), any());
    }

    @Test
    public void testInitialPlan() throws Exception {
        Plan plan = uninstallScheduler.getPlan();
        List<Status> expected = Arrays.asList(Status.PENDING, Status.PENDING, Status.PENDING, Status.PENDING);
        Assert.assertEquals(expected, PlanTestUtils.getStepStatuses(plan));
    }

    @Test
    public void testInitialPlanTaskResourceOverlap() throws Exception {
        // Add TASK_B, which overlaps with TASK_A.
        stateStore = new StateStore(new MemPersister());
        stateStore.storeTasks(Arrays.asList(TASK_A, TASK_B));
        stateStore.storeFrameworkId(TestConstants.FRAMEWORK_ID);
        uninstallScheduler = new TestScheduler(TestConstants.SERVICE_NAME, stateStore, mockConfigStore, true);
        uninstallScheduler.registered(mockSchedulerDriver, TestConstants.FRAMEWORK_ID, TestConstants.MASTER_INFO);

        Plan plan = uninstallScheduler.getPlan();
        // 4 unique resources + deregister step.
        List<Status> expected = Arrays.asList(Status.PENDING, Status.PENDING, Status.PENDING,
                Status.PENDING, Status.PENDING);
        Assert.assertEquals(expected, PlanTestUtils.getStepStatuses(plan));
    }

    @Test
    public void testUninstallStepsPrepared() throws Exception {
        // Initial call to resourceOffers() will return all steps from resource phase as candidates
        // regardless of the offers sent in, and will start the steps.
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Arrays.asList(getOffer()));
        uninstallScheduler.awaitOffersProcessed();
        Plan plan = uninstallScheduler.getPlan();
        List<Status> expected = Arrays.asList(Status.PREPARED, Status.PREPARED, Status.PREPARED, Status.PENDING);
        Assert.assertEquals(expected, PlanTestUtils.getStepStatuses(plan));
    }

    @Test
    public void testUninstallStepsComplete() throws Exception {
        Protos.Offer offer = OfferTestUtils.getOffer(Arrays.asList(RESERVED_RESOURCE_1, RESERVED_RESOURCE_2));
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Collections.singletonList(offer));
        uninstallScheduler.awaitOffersProcessed();
        Plan plan = uninstallScheduler.getPlan();
        List<Status> expected = Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.PREPARED, Status.PENDING);
        Assert.assertEquals(plan.toString(), expected, PlanTestUtils.getStepStatuses(plan));

        offer = OfferTestUtils.getOffer(Collections.singletonList(RESERVED_RESOURCE_3));
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Collections.singletonList(offer));
        uninstallScheduler.awaitOffersProcessed();
        expected = Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.COMPLETE, Status.PENDING);
        Assert.assertEquals(expected, PlanTestUtils.getStepStatuses(plan));
    }

    @Test
    public void testPlanCompletes() throws Exception {
        Protos.Offer offer = OfferTestUtils.getOffer(Arrays.asList(
                RESERVED_RESOURCE_1, RESERVED_RESOURCE_2, RESERVED_RESOURCE_3));
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Collections.singletonList(offer));
        uninstallScheduler.awaitOffersProcessed();

        // Turn the crank once to start the first Step (the DeleteServiceRootPathStep) in the serial misc-phase
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Arrays.asList(getOffer()));
        uninstallScheduler.awaitOffersProcessed();
        Plan plan = uninstallScheduler.getPlan();
        List<Status> expected = Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.COMPLETE, Status.COMPLETE);
        Assert.assertEquals(plan.toString(), expected, PlanTestUtils.getStepStatuses(plan));
        Assert.assertTrue(plan.isComplete());
    }

    @Test
    public void testApiServerNotReadyDecline() throws InterruptedException {
        UninstallScheduler uninstallScheduler =
                new TestScheduler(TestConstants.SERVICE_NAME, stateStore, mockConfigStore, false);
        uninstallScheduler.registered(mockSchedulerDriver, TestConstants.FRAMEWORK_ID, TestConstants.MASTER_INFO);

        Protos.Offer offer = OfferTestUtils.getOffer(Collections.singletonList(RESERVED_RESOURCE_3));
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Collections.singletonList(offer));
        uninstallScheduler.awaitOffersProcessed();
        verify(mockSchedulerDriver, times(1)).declineOffer(any());
    }

    @Test
    public void testAllButDeregisteredPlanCompletes() throws Exception {
        // No framework ID is set yet, and there are no tasks, and no SchedulerDriver
        UninstallScheduler uninstallScheduler = new UninstallScheduler(
                TestConstants.SERVICE_NAME,
                new StateStore(new MemPersister()),
                mockConfigStore,
                OfferRequirementTestUtils.getTestSchedulerFlags());
        // Returns a simple placeholder plan with status COMPLETE
        Assert.assertTrue(uninstallScheduler.getPlan().isComplete());
        Assert.assertTrue(uninstallScheduler.getPlan().getChildren().isEmpty());
    }

    @Test
    public void testTLSCleanupInvoked() throws Exception {
        UninstallScheduler uninstallScheduler = new TestScheduler(
                TestConstants.SERVICE_NAME,
                stateStore,
                mockConfigStore,
                Optional.of(mockSecretsClient),
                true);
        Plan plan = uninstallScheduler.getPlan();

        when(mockSecretsClient.list(TestConstants.SERVICE_NAME))
                .thenReturn(Collections.emptyList());

        // Run through the task cleanup phase
        uninstallScheduler.registered(mockSchedulerDriver, TestConstants.FRAMEWORK_ID, TestConstants.MASTER_INFO);
        Protos.Offer offer = OfferTestUtils.getOffer(Arrays.asList(
                RESERVED_RESOURCE_1, RESERVED_RESOURCE_2, RESERVED_RESOURCE_3));
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Collections.singletonList(offer));
        uninstallScheduler.awaitOffersProcessed();
        List<Status> expected = Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.COMPLETE, Status.PENDING, Status.PENDING);
        Assert.assertEquals(plan.toString(), expected, PlanTestUtils.getStepStatuses(plan));

        // Then the TLS cleanup phase
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Arrays.asList(getOffer()));
        uninstallScheduler.awaitOffersProcessed();
        expected = Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.COMPLETE, Status.COMPLETE, Status.PENDING);
        Assert.assertEquals(plan.toString(), expected, PlanTestUtils.getStepStatuses(plan));

        verify(mockSecretsClient, times(1)).list(TestConstants.SERVICE_NAME);

        // Then the final Deregister phase
        uninstallScheduler.resourceOffers(mockSchedulerDriver, Arrays.asList(getOffer()));
        uninstallScheduler.awaitOffersProcessed();
        expected = Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.COMPLETE, Status.COMPLETE, Status.COMPLETE);
        Assert.assertEquals(plan.toString(), expected, PlanTestUtils.getStepStatuses(plan));

        Assert.assertTrue(uninstallScheduler.getPlan().isComplete());
    }

    /**
     * This is an unfortunate workaround for not being able to use a Spy on the UninstallScheduler instance.
     */
    private static class TestScheduler extends UninstallScheduler {
        private final boolean apiServerReady;

        TestScheduler(
                String serviceName,
                StateStore stateStore,
                ConfigStore<ServiceSpec> configStore,
                boolean apiServerReady) {
            this(serviceName, stateStore, configStore, Optional.empty(), apiServerReady);
        }

        TestScheduler(
                String serviceName,
                StateStore stateStore,
                ConfigStore<ServiceSpec> configStore,
                Optional<SecretsClient> secretsClient,
                boolean apiServerReady) {
            super(
                    serviceName,
                    stateStore,
                    configStore,
                    OfferRequirementTestUtils.getTestSchedulerFlags(),
                    secretsClient);
            this.apiServerReady = apiServerReady;
        }

        @Override
        public boolean apiServerReady() {
            return apiServerReady;
        }
    }

    private Protos.Offer getOffer() {
        return getOffer(UUID.randomUUID().toString());
    }

    private Protos.Offer getOffer(String id) {
        return Protos.Offer.newBuilder()
                .setId(Protos.OfferID.newBuilder().setValue(id))
                .setFrameworkId(TestConstants.FRAMEWORK_ID)
                .setSlaveId(TestConstants.AGENT_ID)
                .setHostname(TestConstants.HOSTNAME)
                .build();
    }
}
