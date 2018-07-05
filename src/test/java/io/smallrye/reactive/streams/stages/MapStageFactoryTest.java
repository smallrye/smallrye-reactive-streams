package io.smallrye.reactive.streams.stages;

import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import org.eclipse.microprofile.reactive.streams.ReactiveStreams;
import org.eclipse.microprofile.reactive.streams.spi.Stage;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the behavior of the {@link MapStageFactory}.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class MapStageFactoryTest extends StageTestBase {

  private final MapStageFactory factory = new MapStageFactory();

  @Test
  public void create() throws ExecutionException, InterruptedException {
    Flowable<Integer> flowable = Flowable.fromArray(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
      .subscribeOn(Schedulers.computation());

    List<String> list = ReactiveStreams.fromPublisher(flowable)
      .filter(i -> i < 4)
      .map(this::square)
      .map(this::asString)
      .toList()
      .run(engine).toCompletableFuture().get();

    assertThat(list).containsExactly("1", "4", "9");
  }

  @Test
  public void createOnVertxContext() {
    Flowable<Integer> flowable = Flowable.fromArray(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
      .subscribeOn(Schedulers.computation());

    Callable<CompletionStage<List<String>>> callable = () ->
      ReactiveStreams.fromPublisher(flowable)
        .filter(i -> i < 4)
        .map(this::square)
        .map(this::asString)
        .toList()
        .run(engine);

    executeOnEventLoop(callable).assertSuccess(Arrays.asList("1", "4", "9"));
  }


  private Integer square(int i) {
    return i * i;
  }

  private String asString(int i) {
    return Objects.toString(i);
  }

  @Test(expected = NullPointerException.class)
  public void createWithoutStage() {
    factory.create(null, null);
  }

  @Test(expected = NullPointerException.class)
  public void createWithoutFunction() {
    factory.create(null, new Stage.Map(null));
  }

}