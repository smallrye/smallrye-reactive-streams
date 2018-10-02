package io.smallrye.reactive.streams;

import io.reactivex.Flowable;
import io.smallrye.reactive.streams.spi.ExecutionModel;
import io.smallrye.reactive.streams.stages.*;
import io.smallrye.reactive.streams.utils.ConnectableProcessor;
import io.smallrye.reactive.streams.utils.WrappedProcessor;
import org.eclipse.microprofile.reactive.streams.CompletionSubscriber;
import org.eclipse.microprofile.reactive.streams.spi.Graph;
import org.eclipse.microprofile.reactive.streams.spi.ReactiveStreamsEngine;
import org.eclipse.microprofile.reactive.streams.spi.Stage;
import org.eclipse.microprofile.reactive.streams.spi.UnsupportedStageException;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletionStage;

public class Engine implements ReactiveStreamsEngine {

    private static final String INVALID_STAGE_MSG = "Invalid stage ";

    private static final Map<Class, ProcessingStageFactory> PROCESSOR_STAGES = new HashMap<>();
    private static final Map<Class, PublisherStageFactory> PUBLISHER_STAGES = new HashMap<>();
    private static final Map<Class, TerminalStageFactory> SUBSCRIBER_STAGES = new HashMap<>();

    private static final ExecutionModel TRANSFORMER;

    static {
        PROCESSOR_STAGES.put(Stage.Distinct.class, new DistinctStageFactory());
        PROCESSOR_STAGES.put(Stage.Filter.class, new FilterStageFactory());
        PROCESSOR_STAGES.put(Stage.FlatMap.class, new FlatMapStageFactory());
        PROCESSOR_STAGES.put(Stage.FlatMapCompletionStage.class, new FlatMapCompletionStageFactory());
        PROCESSOR_STAGES.put(Stage.FlatMapIterable.class, new FlatMapIterableStageFactory());
        PROCESSOR_STAGES.put(Stage.Map.class, new MapStageFactory());
        PROCESSOR_STAGES.put(Stage.Peek.class, new PeekStageFactory());
        PROCESSOR_STAGES.put(Stage.OnComplete.class, new OnCompleteStageFactory());
        PROCESSOR_STAGES.put(Stage.OnTerminate.class, new OnTerminateStageFactory());
        PROCESSOR_STAGES.put(Stage.OnError.class, new OnErrorStageFactory());
        PROCESSOR_STAGES.put(Stage.OnErrorResume.class, new OnErrorResumeStageFactory());
        PROCESSOR_STAGES.put(Stage.OnErrorResumeWith.class, new OnErrorResumeWithStageFactory());
        PROCESSOR_STAGES.put(Stage.ProcessorStage.class, new ProcessorStageFactory());
        PROCESSOR_STAGES.put(Stage.TakeWhile.class, new TakeWhileStageFactory());
        PROCESSOR_STAGES.put(Stage.DropWhile.class, new DropWhileStageFactory());
        PROCESSOR_STAGES.put(Stage.Limit.class, new LimitStageFactory());
        PROCESSOR_STAGES.put(Stage.Skip.class, new SkipStageFactory());

        PUBLISHER_STAGES.put(Stage.Concat.class, new ConcatStageFactory());
        PUBLISHER_STAGES.put(Stage.Failed.class, new FailedPublisherStageFactory());
        PUBLISHER_STAGES.put(Stage.Of.class, new FromIterableStageFactory());
        PUBLISHER_STAGES.put(Stage.PublisherStage.class, new FromPublisherStageFactory());
        PUBLISHER_STAGES.put(Stage.FromCompletionStage.class, new FromCompletionStageFactory());
        PUBLISHER_STAGES.put(Stage.FromCompletionStageNullable.class, new FromCompletionStageNullableFactory());


        SUBSCRIBER_STAGES.put(Stage.Cancel.class, new CancelStageFactory());
        SUBSCRIBER_STAGES.put(Stage.Collect.class, new CollectStageFactory());
        SUBSCRIBER_STAGES.put(Stage.FindFirst.class, new FindFirstStageFactory());
        SUBSCRIBER_STAGES.put(Stage.SubscriberStage.class, new SubscriberStageFactory());

        ServiceLoader<ExecutionModel> loader = ServiceLoader.load(ExecutionModel.class);
        Iterator<ExecutionModel> iterator = loader.iterator();
        if (iterator.hasNext()) {
            TRANSFORMER = iterator.next();
        } else {
            TRANSFORMER = i -> i;
        }
    }

    /**
     * Calls the execution model transformer.
     *
     * @param flowable the flowable
     * @param <T>      the type of data
     * @return the decorated flowable if needed
     */
    private static <T> Flowable<T> injectThreadSwitchIfNeeded(Flowable<T> flowable) {
        return TRANSFORMER.transform(flowable);
    }

    @Override
    public <T> Publisher<T> buildPublisher(Graph graph) {
        Flowable<T> flowable = null;
        for (Stage stage : graph.getStages()) {
            if (flowable == null) {
                flowable = createPublisher(stage);
            } else {
                flowable = applyProcessors(flowable, stage);
            }
        }
        return flowable;
    }

    @Override
    public <T, R> CompletionSubscriber<T, R> buildSubscriber(Graph graph) {
        Processor<T, T> processor = new ConnectableProcessor<>();

        Flowable<T> flowable = Flowable.fromPublisher(processor);
        for (Stage stage : graph.getStages()) {
            if (stage.hasOutlet()) {
                flowable = applyProcessors(flowable, stage);
            } else {
                CompletionStage<R> result = applySubscriber(injectThreadSwitchIfNeeded(flowable), stage);
                return CompletionSubscriber.of(processor, result);
            }
        }

        throw new IllegalArgumentException("The graph does not have a valid final stage");
    }

    @Override
    public <T, R> Processor<T, R> buildProcessor(Graph graph) {
        Processor<T, T> processor = new ConnectableProcessor<>();

        Flowable<T> flowable = Flowable.fromPublisher(processor);
        for (Stage stage : graph.getStages()) {
            flowable = applyProcessors(flowable, stage);
        }

        //noinspection unchecked
        return (Processor<T, R>) new WrappedProcessor<>(processor, flowable);
    }

    @Override
    public <T> CompletionStage<T> buildCompletion(Graph graph) {
        Flowable<?> flowable = null;
        for (Stage stage : graph.getStages()) {
            if (flowable == null) {
                flowable = createPublisher(stage);
            } else if (stage.hasOutlet()) {
                flowable = applyProcessors(flowable, stage);
            } else {
                return applySubscriber(flowable, stage);
            }
        }

        throw new IllegalArgumentException("Graph did not have terminal stage");
    }

    private <I, O> Flowable<O> applyProcessors(Flowable<I> flowable, Stage stage) {
        if (!stage.hasOutlet() && !stage.hasInlet()) {
            throw new IllegalArgumentException(INVALID_STAGE_MSG + stage
                    + " - expected one inlet and one outlet.");
        }
        ProcessingStageFactory factory = PROCESSOR_STAGES.get(stage.getClass());
        if (factory == null) {
            throw new UnsupportedStageException(stage);
        }
        @SuppressWarnings("unchecked") ProcessingStage<I, O> ps = factory.create(this, stage);
        return injectThreadSwitchIfNeeded(ps.process(flowable));
    }

    private <T, R> CompletionStage<R> applySubscriber(Flowable<T> flowable, Stage stage) {
        if (stage.hasOutlet() || !stage.hasInlet()) {
            throw new IllegalArgumentException(INVALID_STAGE_MSG + stage
                    + " - expected one inlet and no outlet.");
        }
        TerminalStageFactory factory = SUBSCRIBER_STAGES.get(stage.getClass());
        if (factory == null) {
            throw new UnsupportedStageException(stage);
        }
        @SuppressWarnings("unchecked") TerminalStage<T, R> ps = factory.create(this, stage);
        return ps.toCompletionStage(injectThreadSwitchIfNeeded(flowable));
    }

    private <O> Flowable<O> createPublisher(Stage stage) {
        if (!stage.hasOutlet() || stage.hasInlet()) {
            throw new IllegalArgumentException(INVALID_STAGE_MSG + stage
                    + " - expected no inlet and one outlet.");
        }
        PublisherStageFactory factory = PUBLISHER_STAGES.get(stage.getClass());
        if (factory == null) {
            throw new UnsupportedStageException(stage);
        }
        @SuppressWarnings("unchecked") PublisherStage<O> ps = factory.create(this, stage);
        return injectThreadSwitchIfNeeded(ps.create());
    }

}