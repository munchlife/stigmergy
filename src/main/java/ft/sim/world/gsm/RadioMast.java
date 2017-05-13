package ft.sim.world.gsm;

import ft.sim.world.journey.JourneyHelper;
import ft.sim.world.map.GlobalMap;
import ft.sim.world.train.Train;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by sina on 08/05/2017.
 */
public class RadioMast {

  private static Map<GlobalMap, RadioMast> instances = new HashMap<>();
  private GlobalMap world;

  private RadioMast(GlobalMap map) {
    this.world = map;
  }

  public static RadioMast getInstance(GlobalMap world) {
    return instances.computeIfAbsent(world, RadioMast::new);
  }

  public void passMessageToTrainBehind(Train train, RadioSignal signal) {
    JourneyHelper.getInstance(world).getTrainBehind(train).ifPresent(t -> t.ping(signal));
  }

}