package it.unipi.di.acube.wat.servlet;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.twitter.concurrent.AsyncQueue;
import com.twitter.concurrent.NamedPoolThreadFactory;
import com.twitter.finagle.ClientConnection;
import com.twitter.finagle.Service;
import com.twitter.finagle.ServiceFactory;
import com.twitter.finagle.http.Request;
import com.twitter.finagle.http.Response;
import com.twitter.util.Future;
import com.twitter.util.FuturePool;
import com.twitter.util.FuturePools;
import com.twitter.util.Time;

import it.unipi.di.acube.finagle.servlet.FinagleServiceFactoryProvider;
import it.unipi.di.acubelab.wat.annotator.BaseAnnotator;
import it.unipi.di.acubelab.wat.server.finch.RESTService;
import it.unipi.di.acubelab.wat.server.thrift.SafeAnnotatorService;
import it.unipi.di.acubelab.wat.utils.WAT;
import scala.collection.immutable.HashMap;
import scala.collection.immutable.Map;
import scala.runtime.BoxedUnit;

public class WATAdapter implements FinagleServiceFactoryProvider {
	private final static Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
	public final String CONTEXT_PARAM_NUM_THREADS = "it.unipi.di.acube.wat.servlet.num_threads";
	public final String CONTEXT_PARAM_CONFIG_FILE = "it.unipi.di.acube.wat.servlet.config_file";

	@Override
	public ServiceFactory<Request, Response> provide(ServletConfig config) {
		return new WATServiceFactory(config);
	}

	private class WATServiceFactory extends ServiceFactory<Request, Response> {
		public static final long TERMINATION_TIMEOUT_SEC = 60;
		private Future<Service<Request, Response>> finagleService;
		private ExecutorService threadPool;

		WATServiceFactory(ServletConfig config) {
			String watConfigFile = config.getServletContext().getInitParameter(CONTEXT_PARAM_CONFIG_FILE);
			if (watConfigFile != null && !watConfigFile.isEmpty()) {
				LOG.info("Loading WAT configuration from {}", watConfigFile);
				WAT.loadConfigFile(watConfigFile);
			} else {
				LOG.error("You must specify config file in context parameter {}", CONTEXT_PARAM_CONFIG_FILE);
				throw new RuntimeException("WAT config file not specified");
			}
			
			String futurePoolName = "WATAnnotatorPool";

			int numThreads = Integer.parseInt((String) config.getServletContext().getInitParameter(CONTEXT_PARAM_NUM_THREADS));
			LOG.info("Creating WAT Finagle service with {} threads.", numThreads);

			threadPool = Executors.newFixedThreadPool(numThreads, new NamedPoolThreadFactory(futurePoolName, true));
			FuturePool futurePool = FuturePools.newFuturePool(threadPool);
			Map<String, AsyncQueue<BaseAnnotator>> map = new HashMap<>();
			SafeAnnotatorService s = new SafeAnnotatorService(map, futurePool);
			
			Service<Request, Response> service = RESTService.create(s);

			this.finagleService = Future.value(service);
		}

		@Override
		public Future<BoxedUnit> close(Time arg0) {
			try {
				LOG.info("Awaiting termination of annotations threads.");
				threadPool.shutdownNow();
				threadPool.awaitTermination(TERMINATION_TIMEOUT_SEC, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return Future.value(BoxedUnit.UNIT);
		}

		@Override
		public Future<Service<Request, Response>> apply(ClientConnection arg0) {
			return finagleService;
		}
	}
}
