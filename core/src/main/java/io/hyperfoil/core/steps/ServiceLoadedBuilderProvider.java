package io.hyperfoil.core.steps;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import io.hyperfoil.api.config.BenchmarkDefinitionException;
import io.hyperfoil.api.config.ServiceLoadedBuilder;

public class ServiceLoadedBuilderProvider<T> {
   private static final Map<Class<ServiceLoadedBuilder.Factory<?>>, ServiceLoader<ServiceLoadedBuilder.Factory<?>>> SERVICE_LOADERS = new HashMap<>();

   private final Class<? extends ServiceLoadedBuilder.Factory<T>> factoryClazz;
   private final Consumer<T> consumer;

   public static Iterable<ServiceLoadedBuilder.Factory<?>> factories(Class<ServiceLoadedBuilder.Factory<?>> clazz) {
      ServiceLoader<ServiceLoadedBuilder.Factory<?>> loader = SERVICE_LOADERS.get(clazz);
      if (loader == null) {
         loader = ServiceLoader.load(clazz);
         SERVICE_LOADERS.put(clazz, loader);
      }
      return loader;
   }

   private static ServiceLoadedBuilder.Factory<?> factory(Class<ServiceLoadedBuilder.Factory<?>> clazz, String name) {
      Iterable<ServiceLoadedBuilder.Factory<?>> loader = factories(clazz);
      ServiceLoadedBuilder.Factory<?> factory = null;
      for (ServiceLoadedBuilder.Factory<?> f : loader) {
         if (f.name().equals(name)) {
            if (factory != null) {
               throw new BenchmarkDefinitionException("Two classes ('" + factory.getClass().getName() +
                     "' and '" + f.getClass().getName() + "') provide builders for name '" + name + "' and type '" +
                     clazz.getName() + "'");
            }
            factory = f;
         }
      }
      if (factory == null) {
         throw new BenchmarkDefinitionException("No class provides builders for name '" + name + "' and type '" + clazz.getName() + "'");
      }
      return factory;
   }

   public ServiceLoadedBuilderProvider(Class<? extends ServiceLoadedBuilder.Factory<T>> factoryClazz, Consumer<T> consumer) {
      this.factoryClazz = factoryClazz;
      this.consumer = consumer;
   }

   public ServiceLoadedBuilder forName(String name, String param) {
      return factory((Class) factoryClazz, name).newBuilder(consumer, param);
   }
}
