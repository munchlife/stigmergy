package ft.sim;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

import ft.sim.experiment.ExperimentController;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Hello world!
 */
@SpringBootApplication
@EnableAsync
public class App implements ApplicationRunner {

  private static final transient Logger logger = LoggerFactory.getLogger(App.class);
  private static ConfigurableApplicationContext context = null;

  public static void main(String[] args) {
    AppConfig.init();
    context = new SpringApplicationBuilder(App.class).web(!AppConfig.isNonInteractive).run(args);
    //context = SpringApplication.run(App.class, args);

    if (AppConfig.isNonInteractive) {
      logger.info("Running in Non-interactive mode");
      ExperimentController.getInstance().start();
    }
  }

  public static void experimentCompleted() {
    context.close();
    //SpringApplication.exit(context, () -> 0);
  }

  @Override
  public void run(ApplicationArguments applicationArguments) throws Exception {
    List<String> baseOptions = applicationArguments.getNonOptionArgs();
    AppConfig.isNonInteractive = baseOptions.contains("experiment");
    boolean mapsProvided = applicationArguments.containsOption("maps");
    if (mapsProvided) {
      List<String> maps = applicationArguments.getOptionValues("maps");
      maps.forEach(map -> AppConfig.experimentMaps.addAll(Arrays.asList(map.split(","))));
    }
  }

  /*@Override
  public void run(String... args) throws Exception {
    for(String arg: args)
      logger.warn("arg: {}", arg);
  }*/

  public static class AppConfig {

    public static boolean isNonInteractive = true;
    public static Set<String> experimentMaps = new LinkedHashSet<>();
    public static String outputDir = "./results";

    public static void init() {
      // create output dir
      File basedirFile = (new File(AppConfig.outputDir));
      if (!basedirFile.exists()) {
        boolean createdResultsDir = basedirFile.mkdirs();
        if (!createdResultsDir) {
          throw new IllegalStateException("Failed to create results directory");
        }
      }
    }
  }
}
