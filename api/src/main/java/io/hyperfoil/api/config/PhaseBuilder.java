package io.hyperfoil.api.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.hyperfoil.function.SerializableSupplier;
import io.hyperfoil.impl.FutureSupplier;
import io.hyperfoil.util.Util;

/**
 * The builder creates a matrix of phases (not just single phase); we allow multiple iterations of a phase
 * (with increasing number of users) and multiple forks (different scenarios, but same configuration).
 */
public abstract class PhaseBuilder<PB extends PhaseBuilder> {
   protected final String name;
   protected final BenchmarkBuilder parent;
   protected long startTime = -1;
   protected Collection<PhaseReference> startAfter = new ArrayList<>();
   protected Collection<PhaseReference> startAfterStrict = new ArrayList<>();
   protected Collection<PhaseReference> terminateAfterStrict = new ArrayList<>();
   protected long duration = -1;
   protected long maxDuration = -1;
   protected int maxIterations = 1;
   protected boolean forceIterations = false;
   protected List<PhaseForkBuilder> forks = new ArrayList<>();

   protected PhaseBuilder(BenchmarkBuilder parent, String name) {
      this.name = name;
      this.parent = parent;
      parent.addPhase(name, this);
   }

   public static Phase.Noop noop(SerializableSupplier<Benchmark> benchmark, int id, String iterationName, List<String> startAfter, List<String> startAfterStrict, List<String> terminateAfterStrict) {
      FutureSupplier<Phase> ps = new FutureSupplier<>();
      Scenario scenario = new Scenario(new Sequence[0], new Sequence[0], new String[0], new String[0]);
      Phase.Noop phase = new Phase.Noop(benchmark, id, iterationName, startAfter, startAfterStrict, terminateAfterStrict, scenario);
      ps.set(phase);
      return phase;
   }

   public BenchmarkBuilder endPhase() {
      return parent;
   }

   public String name() {
      return name;
   }

   public ScenarioBuilder scenario() {
      if (forks.isEmpty()) {
         PhaseForkBuilder fork = new PhaseForkBuilder(this, null);
         forks.add(fork);
         return fork.scenario;
      } else if (forks.size() == 1 && forks.get(0).name == null) {
         throw new BenchmarkDefinitionException("Scenario for " + name + " already set!");
      } else {
         throw new BenchmarkDefinitionException("Scenario is forked; you need to specify another fork.");
      }
   }

   @SuppressWarnings("unchecked")
   private PB self() {
      return (PB) this;
   }

   public PhaseForkBuilder fork(String name) {
      if (forks.size() == 1 && forks.get(0).name == null) {
         throw new BenchmarkDefinitionException("Scenario for " + name + " already set!");
      } else {
         PhaseForkBuilder fork = new PhaseForkBuilder(this, name);
         forks.add(fork);
         return fork;
      }
   }

   public PB startTime(long startTime) {
      this.startTime = startTime;
      return self();

   }

   public PB startTime(String startTime) {
      return startTime(Util.parseToMillis(startTime));
   }

   public PB startAfter(String phase) {
      this.startAfter.add(new PhaseReference(phase, RelativeIteration.NONE, null));
      return self();
   }

   public PB startAfter(PhaseReference phase) {
      this.startAfter.add(phase);
      return self();
   }

   public PB startAfterStrict(String phase) {
      this.startAfterStrict.add(new PhaseReference(phase, RelativeIteration.NONE, null));
      return self();
   }

   public PB startAfterStrict(PhaseReference phase) {
      this.startAfterStrict.add(phase);
      return self();
   }

   public PB duration(long duration) {
      this.duration = duration;
      return self();
   }

   public PB duration(String duration) {
      return duration(Util.parseToMillis(duration));
   }

   public PB maxDuration(long maxDuration) {
      this.maxDuration = maxDuration;
      return self();
   }

   public PB maxDuration(String duration) {
      return maxDuration(Util.parseToMillis(duration));
   }

   public PB maxIterations(int iterations) {
      this.maxIterations = iterations;
      return self();
   }

   public void prepareBuild() {
      forks.forEach(fork -> fork.scenario.prepareBuild());
   }

   public Collection<Phase> build(SerializableSupplier<Benchmark> benchmark, AtomicInteger idCounter) {
      // normalize fork weights first
      if (forks.isEmpty()) {
         throw new BenchmarkDefinitionException("Scenario for " + name + " is not defined.");
      } else if (forks.size() == 1 && forks.get(0).name != null) {
         throw new BenchmarkDefinitionException(name + " has single fork: define scenario directly.");
      }
      boolean hasForks = forks.size() > 1;
      forks.removeIf(fork -> fork.weight <= 0);

      double sumWeight = forks.stream().mapToDouble(f -> f.weight).sum();
      forks.forEach(f -> f.weight /= sumWeight);

      // create matrix of iteration|fork phases
      List<Phase> phases = IntStream.range(0, maxIterations)
            .mapToObj(iteration -> forks.stream().map(f -> {
               FutureSupplier<Phase> ps = new FutureSupplier<>();
               Phase phase = buildPhase(benchmark, ps, idCounter.getAndIncrement(), iteration, f);
               ps.set(phase);
               return phase;
            }))
            .flatMap(Function.identity()).collect(Collectors.toList());
      if (maxIterations > 1 || forceIterations) {
         if (hasForks) {
            // add phase covering forks in each iteration
            IntStream.range(0, maxIterations).mapToObj(iteration -> {
               String iterationName = formatIteration(name, iteration);
               List<String> forks = this.forks.stream().map(f -> iterationName + "/" + f.name).collect(Collectors.toList());
               return noop(benchmark, idCounter.getAndIncrement(), iterationName, forks, Collections.emptyList(), forks);
            }).forEach(phases::add);
         }
         // Referencing phase with iterations with RelativeIteration.NONE means that it starts after all its iterations
         List<String> lastIteration = Collections.singletonList(formatIteration(name, maxIterations - 1));
         phases.add(noop(benchmark, idCounter.getAndIncrement(), name, lastIteration, Collections.emptyList(), lastIteration));
      } else if (hasForks) {
         // add phase covering forks
         List<String> forks = this.forks.stream().map(f -> name + "/" + f.name).collect(Collectors.toList());
         phases.add(noop(benchmark, idCounter.getAndIncrement(), name, forks, Collections.emptyList(), forks));
      }
      return phases;
   }


   protected abstract Phase buildPhase(SerializableSupplier<Benchmark> benchmark, SerializableSupplier<Phase> ps, int phaseId, int iteration, PhaseForkBuilder f);

   int sliceValue(String property, int value, double ratio) {
      double sliced = value * ratio;
      long rounded = Math.round(sliced);
      if (Math.abs(rounded - sliced) > 0.0001) {
         throw new BenchmarkDefinitionException("Cannot slice phase " + name + ", property " + property + " cleanly: " + value + " * " + ratio + " is not an integer.");
      }
      return (int) rounded;
   }

   int numAgents() {
      return Math.max(parent.numAgents(), 1);
   }

   String iterationName(int iteration, String forkName) {
      if (maxIterations == 1 && !forceIterations) {
         assert iteration == 0;
         if (forkName == null) {
            return name;
         } else {
            return name + "/" + forkName;
         }
      } else {
         String iterationName = formatIteration(name, iteration);
         if (forkName == null) {
            return iterationName;
         } else {
            return iterationName + "/" + forkName;
         }
      }
   }

   private String formatIteration(String name, int iteration) {
      return String.format("%s/%03d", name, iteration);
   }

   long iterationStartTime(int iteration) {
      return iteration == 0 ? startTime : -1;
   }

   // Identifier for phase + fork, omitting iteration
   String sharedResources(PhaseForkBuilder fork) {
      if (fork == null || fork.name == null) {
         return name;
      } else {
         return name + "/" + fork;
      }
   }

   Collection<String> iterationReferences(Collection<PhaseReference> refs, int iteration, boolean addSelfPrevious) {
      Collection<String> names = new ArrayList<>();
      for (PhaseReference ref : refs) {
         switch (ref.iteration) {
            case NONE:
               names.add(ref.phase);
               break;
            case PREVIOUS:
               if (iteration > 0) {
                  names.add(formatIteration(ref.phase, iteration - 1));
               }
               break;
            case SAME:
               names.add(formatIteration(ref.phase, iteration));
               break;
            default:
               throw new IllegalArgumentException();
         }
      }
      if (addSelfPrevious && iteration > 0) {
         names.add(formatIteration(name, iteration - 1));
      }
      return names;
   }

   public void readForksFrom(PhaseBuilder<?> other) {
      for (PhaseForkBuilder builder : other.forks) {
         fork(builder.name).readFrom(builder);
      }
   }

   public PB forceIterations(boolean force) {
      this.forceIterations = force;
      return self();
   }

   public static class AtOnce extends PhaseBuilder<AtOnce> {
      private int users;
      private int usersIncrement;

      AtOnce(BenchmarkBuilder parent, String name, int users) {
         super(parent, name);
         this.users = users;
      }

      public AtOnce users(int users) {
         this.users = users;
         return this;
      }

      public AtOnce users(int base, int increment) {
         this.users = base;
         this.usersIncrement = increment;
         return this;
      }

      @Override
      public Phase.AtOnce buildPhase(SerializableSupplier<Benchmark> benchmark, SerializableSupplier<Phase> phase, int id, int i, PhaseForkBuilder f) {
         if (users <= 0) {
            throw new BenchmarkDefinitionException("Phase " + name + ".users must be positive.");
         }
         return new Phase.AtOnce(benchmark, id, iterationName(i, f.name), f.scenario.build(phase),
               iterationStartTime(i), iterationReferences(startAfter, i, false),
               iterationReferences(startAfterStrict, i, true), iterationReferences(terminateAfterStrict, i, false), duration,
               maxDuration,
               sharedResources(f), sliceValue("users", users + usersIncrement * i, f.weight / numAgents()));
      }
   }

   public static class Always extends PhaseBuilder<Always> {
      private int users;
      private int usersIncrement;

      Always(BenchmarkBuilder parent, String name, int users) {
         super(parent, name);
         this.users = users;
      }

      @Override
      public Phase.Always buildPhase(SerializableSupplier<Benchmark> benchmark, SerializableSupplier<Phase> phase, int id, int i, PhaseForkBuilder f) {
         if (users <= 0) {
            throw new BenchmarkDefinitionException("Phase " + name + ".users must be positive.");
         }
         return new Phase.Always(benchmark, id, iterationName(i, f.name), f.scenario.build(phase), iterationStartTime(i),
               iterationReferences(startAfter, i, false), iterationReferences(startAfterStrict, i, true),
               iterationReferences(terminateAfterStrict, i, false), duration, maxDuration,
               sharedResources(f),
               sliceValue("users", this.users + usersIncrement * i, f.weight / numAgents()));
      }

      public Always users(int users) {
         this.users = users;
         return this;
      }

      public Always users(int base, int increment) {
         this.users = base;
         this.usersIncrement = increment;
         return this;
      }
   }

   public static class RampPerSec extends PhaseBuilder<RampPerSec> {
      private double initialUsersPerSec;
      private double initialUsersPerSecIncrement;
      private double targetUsersPerSec;
      private double targetUsersPerSecIncrement;
      private int maxSessions;
      private boolean variance = true;

      RampPerSec(BenchmarkBuilder parent, String name, double initialUsersPerSec, double targetUsersPerSec) {
         super(parent, name);
         this.initialUsersPerSec = initialUsersPerSec;
         this.targetUsersPerSec = targetUsersPerSec;
      }

      public RampPerSec maxSessions(int maxSessions) {
         this.maxSessions = maxSessions;
         return this;
      }

      @Override
      public Phase.RampPerSec buildPhase(SerializableSupplier<Benchmark> benchmark, SerializableSupplier<Phase> phase, int id, int i, PhaseForkBuilder f) {
         int maxSessions;
         if (this.maxSessions > 0) {
            maxSessions = sliceValue("maxSessions", this.maxSessions, f.weight / numAgents());
         } else {
            double maxInitialUsers = initialUsersPerSec + initialUsersPerSecIncrement * (maxIterations - 1);
            double maxTargetUsers = targetUsersPerSec + targetUsersPerSecIncrement * (maxIterations - 1);
            maxSessions = (int) Math.ceil(Math.max(maxInitialUsers, maxTargetUsers) * f.weight / numAgents());
         }
         if (initialUsersPerSec < 0) {
            throw new BenchmarkDefinitionException("Phase " + name + ".initialUsersPerSec must be non-negative");
         }
         if (targetUsersPerSec < 0) {
            throw new BenchmarkDefinitionException("Phase " + name + ".targetUsersPerSec must be non-negative");
         }
         if (initialUsersPerSec < 0.0001 && targetUsersPerSec < 0.0001) {
            throw new BenchmarkDefinitionException("In phase " + name + " both initialUsersPerSec and targetUsersPerSec are 0");
         }
         return new Phase.RampPerSec(benchmark, id, iterationName(i, f.name), f.scenario.build(phase),
               iterationStartTime(i), iterationReferences(startAfter, i, false),
               iterationReferences(startAfterStrict, i, true), iterationReferences(terminateAfterStrict, i, false),
               duration, maxDuration, sharedResources(f),
               (initialUsersPerSec + initialUsersPerSecIncrement * i) * f.weight / numAgents(),
               (targetUsersPerSec + targetUsersPerSecIncrement * i) * f.weight / numAgents(),
               variance, maxSessions);
      }

      public RampPerSec initialUsersPerSec(double initialUsersPerSec) {
         this.initialUsersPerSec = initialUsersPerSec;
         this.initialUsersPerSecIncrement = 0;
         return this;
      }

      public RampPerSec initialUsersPerSec(double base, double increment) {
         this.initialUsersPerSec = base;
         this.initialUsersPerSecIncrement = increment;
         return this;
      }

      public RampPerSec targetUsersPerSec(double targetUsersPerSec) {
         this.targetUsersPerSec = targetUsersPerSec;
         this.targetUsersPerSecIncrement = 0;
         return this;
      }

      public RampPerSec targetUsersPerSec(double base, double increment) {
         this.targetUsersPerSec = base;
         this.targetUsersPerSecIncrement = increment;
         return this;
      }

      public RampPerSec variance(boolean variance) {
         this.variance = variance;
         return this;
      }
   }

   public static class ConstantPerSec extends PhaseBuilder<ConstantPerSec> {
      private double usersPerSec;
      private double usersPerSecIncrement;
      private int maxSessions;
      private boolean variance = true;

      ConstantPerSec(BenchmarkBuilder parent, String name, double usersPerSec) {
         super(parent, name);
         this.usersPerSec = usersPerSec;
      }

      public ConstantPerSec maxSessions(int maxSessions) {
         this.maxSessions = maxSessions;
         return this;
      }

      @Override
      public Phase.ConstantPerSec buildPhase(SerializableSupplier<Benchmark> benchmark, SerializableSupplier<Phase> phase, int id, int i, PhaseForkBuilder f) {
         int maxSessions;
         if (this.maxSessions <= 0) {
            maxSessions = (int) Math.ceil(f.weight / numAgents() * (usersPerSec + usersPerSecIncrement * (maxIterations - 1)));
         } else {
            maxSessions = sliceValue("maxSessions", this.maxSessions, f.weight / numAgents());
         }
         if (usersPerSec <= 0) {
            throw new BenchmarkDefinitionException("Phase " + name + ".usersPerSec must be positive.");
         }
         return new Phase.ConstantPerSec(benchmark, id, iterationName(i, f.name), f.scenario.build(phase), iterationStartTime(i),
               iterationReferences(startAfter, i, false), iterationReferences(startAfterStrict, i, true),
               iterationReferences(terminateAfterStrict, i, false), duration, maxDuration,
               sharedResources(f),
               (usersPerSec + usersPerSecIncrement * i) * f.weight / numAgents(), variance, maxSessions);
      }

      public ConstantPerSec usersPerSec(double usersPerSec) {
         this.usersPerSec = usersPerSec;
         return this;
      }

      public ConstantPerSec usersPerSec(double base, double increment) {
         this.usersPerSec = base;
         this.usersPerSecIncrement = increment;
         return this;
      }

      public ConstantPerSec variance(boolean variance) {
         this.variance = variance;
         return this;
      }
   }

   public static class Sequentially extends PhaseBuilder<Sequentially> {
      private int repeats;

      protected Sequentially(BenchmarkBuilder parent, String name, int repeats) {
         super(parent, name);
         this.repeats = repeats;
      }

      @Override
      protected Phase buildPhase(SerializableSupplier<Benchmark> benchmark, SerializableSupplier<Phase> phase, int id, int i, PhaseForkBuilder f) {
         if (repeats <= 0) {
            throw new BenchmarkDefinitionException("Phase " + name + ".repeats must be positive");
         }
         return new Phase.Sequentially(benchmark, id, iterationName(i, f.name), f.scenario().build(phase), iterationStartTime(i),
               iterationReferences(startAfter, i, false), iterationReferences(startAfterStrict, i, true),
               iterationReferences(terminateAfterStrict, i, false), duration, maxDuration,
               sharedResources(f), repeats);
      }
   }

   public static class Catalog {
      private final BenchmarkBuilder parent;
      private final String name;

      Catalog(BenchmarkBuilder parent, String name) {
         this.parent = parent;
         this.name = name;
      }

      public AtOnce atOnce(int users) {
         return new PhaseBuilder.AtOnce(parent, name, users);
      }

      public Always always(int users) {
         return new PhaseBuilder.Always(parent, name, users);
      }

      public RampPerSec rampPerSec(int initialUsersPerSec, int targetUsersPerSec) {
         return new RampPerSec(parent, name, initialUsersPerSec, targetUsersPerSec);
      }

      public ConstantPerSec constantPerSec(int usersPerSec) {
         return new ConstantPerSec(parent, name, usersPerSec);
      }

      public Sequentially sequentially(int repeats) {
         return new Sequentially(parent, name, repeats);
      }
   }
}
