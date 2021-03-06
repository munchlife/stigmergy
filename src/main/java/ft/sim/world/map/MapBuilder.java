package ft.sim.world.map;

import static ft.sim.world.RealWorldConstants.ACCELERATION_COEFFICIENT;
import static ft.sim.world.RealWorldConstants.BRAKE_DISTANCE;
import static ft.sim.world.RealWorldConstants.DECELERATION_COEFFICIENT;

import com.google.common.collect.Iterables;
import ft.sim.simulation.Disruptable;
import ft.sim.simulation.Disruptor;
import ft.sim.world.connectables.Connectable;
import ft.sim.world.connectables.LineCondition;
import ft.sim.world.connectables.Station;
import ft.sim.world.connectables.Track;
import ft.sim.world.gsm.RadioMast;
import ft.sim.world.journey.Journey;
import ft.sim.world.journey.JourneyPath;
import ft.sim.world.placeables.ActiveBalise;
import ft.sim.world.placeables.Balise;
import ft.sim.world.placeables.Obstacle;
import ft.sim.world.placeables.PassiveBalise;
import ft.sim.world.placeables.Placeable;
import ft.sim.world.signalling.SignalController;
import ft.sim.world.signalling.SignalType;
import ft.sim.world.signalling.SignalUnit;
import ft.sim.world.train.Train;
import ft.sim.world.train.TrainObjective;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

/**
 * Created by Sina on 30/03/2017.
 */
public class MapBuilder {

  protected transient static final Logger logger = LoggerFactory.getLogger(MapBuilder.class);
  private GlobalMap map = null;

  /*public static GlobalMap buildNewMap() {
    return buildNewMap(DEFAULT_MAP);
  }*/

  public static GlobalMap buildNewMap(String mapName) {
    return buildNewMap(mapName, new GlobalMap(mapName));
  }

  private static GlobalMap buildNewMap(String mapYamlFileName, GlobalMap globalMap) {
    MapBuilder mb = new MapBuilder();
    mb.map = globalMap;
    try {
      if (!mapYamlFileName.endsWith(".yaml")) {
        mapYamlFileName += ".yaml";
      }
      if (!mapYamlFileName.startsWith("maps/") && !new File(mapYamlFileName).isFile()) {
        mapYamlFileName = "maps/" + mapYamlFileName;
      }
      mb.importDefaultConfigurations();
      mb.importMapFile(mapYamlFileName);
      logger.info("Map {} imported successfully.", mapYamlFileName);
    } catch (IOException e) {
      logger.error("failed to import map");
      e.printStackTrace();
      throw new IllegalStateException("Failed to import map!");
    }

    mb.setupWorld();
    return mb.map;
  }

  private void importMapFile(String mapYamlFile) throws IOException {
    logger.info("Loading map: {}", mapYamlFile);

    Resource resource = new ClassPathResource(mapYamlFile);
    InputStream mapInputStream;
    // Try the local jar resources
    File mapFile = new File(mapYamlFile);
    if (mapFile.exists()) {
      mapInputStream = new FileInputStream(mapFile);
    } else {
      if (resource.exists()) {
        mapInputStream = resource.getInputStream();
      } else {
        throw new IllegalStateException("Given file doesn't exist: " + mapYamlFile);
      }
    }

    Yaml yaml = new Yaml();
    Map<String, Object> mapYaml = (Map<String, Object>) yaml.load(mapInputStream);

    setConfigurations((Map<String, Object>) mapYaml.get("simulation"));

    createTracks((Map<String, Object>) mapYaml.get("tracks"));

    createStations((Map<String, Object>) mapYaml.get("stations"));

    createPlaceables((Map<String, Object>) mapYaml.get("placeables"));

    createSwitches((Map<String, Object>) mapYaml.get("switches"));

    createJourneyPaths((Map<String, Object>) mapYaml.get("journeyPaths"));

    createTrains((Map<String, Object>) mapYaml.get("trains"));

    createJourneys((Map<String, Object>) mapYaml.get("journeys"));

  }

  private void importDefaultConfigurations() throws IOException {
    Resource resource = new ClassPathResource("maps/defaults.yaml");
    Yaml yaml = new Yaml();
    Map<String, Object> mapYaml = (Map<String, Object>) yaml.load(resource.getInputStream());

    setConfigurations((Map<String, Object>) mapYaml.get("simulation"));
  }

  private void setupWorld() {
    setTrainsAtStations();
    buildGraph();
    setSignals();
    if (map.isConfiguration("mode", "variable_block")) {
      //FIXME: infinite loop in this method and the one below when tracks branch from a station
      createActiveBalises();
    }
    initBalisePositions();

    // If list of paired tracks is empty, it doesn't pair any tracks
    pairTracks();

    setRadioMasts();

    setIDs();
  }

  private void setIDs() {
    map.getTrains().forEach((id, train) -> train.setID(id));
    map.getStations().forEach((id, station) -> station.setID(id));
    map.getTracks().forEach((id, track) -> track.setID(id));
    map.getSwitches().forEach((id, s) -> s.setID(id));

  }

  private void setRadioMasts() {
    RadioMast.getInstance(map).setFailureRatio((int) map.getConfiguration("gsm_failure_rate"));
    for (Train train : map.getTrains().values()) {
      train.getEcu().setRadioMast(RadioMast.getInstance(map));
    }
  }

  private void pairTracks() {
    map.getTrackPairs()
        .forEach((key, value) -> DualLineHelper.pairTracks(map.getTrack(key), map.getTrack(value)));
  }

  private void initBalisePositions() {
    MapGraph graph = map.getGraph();
    Set<Connectable> roots = graph.getRootConnectables();
    boolean balisesInitialised = true;
    for (Connectable root : roots) {
      try {
        initBalisePosition(graph, root);
      } catch (IllegalStateException e) {
        balisesInitialised = false;
        logger.error("Map {} has branching tracks, which is not supported!", map.getName());
      }
    }
    if (balisesInitialised) {
      logger.info("Balises initliased successfully for map {}", map.getName());
    }
  }

  private void initBalisePosition(MapGraph graph, Connectable root) {
    double length = 0;
    Iterator<Connectable> mapIterator = graph.getIterator(root);
    while (mapIterator.hasNext()) {
      Connectable c = mapIterator.next();

      if (c instanceof Track) {
        List<Balise> balises = ((Track) c).getBalises();
        for (Balise b : balises) {
          int relativePosition = ((Track) c).getPlaceablePosition(b);
          b.setPosition(length + relativePosition);
        }
      }
      length += c.getLength();
    }
  }

  private void buildGraph() {
    for (JourneyPath path : map.getJourneyPaths().values()) {
      List<Connectable> connectables = path.getPath();
      Connectable previousConnectable = null;
      for (Connectable connectable : connectables) {
        map.getGraph().addEdge(previousConnectable, connectable);
        previousConnectable = connectable;
      }
    }
    map.getGraph().buildGraph();

    map.getJourneyPaths().values().forEach(p -> map.getGraph().initJourney(p));
  }

  private void setSignals() {
    if (!map.getGraph().isBuilt()) {
      throw new IllegalStateException("Cannot set signals when map isn't built");
    }
    if (map.isConfiguration("mode", "fixed_block")) {
      setBlockSignals();
      setSwitchSignals();
      setStationSignals();
    } else {
      for (Station station : map.getStations().values()) {
        for (Connectable c : map.getGraph().getChildren(station)) {
          if (c == null) {
            continue;
          }
          if (!(c instanceof Track)) {
            throw new IllegalStateException("a station cannot be connected to non-tracks");
          }
          Track nextTrack = (Track) c;
          SignalController signalController = new SignalController(nextTrack);
          SignalUnit mainSignal = signalController.getMainSignal();
          nextTrack.addBlockSignal(mainSignal, 0);
          station.setNextBlockSignalController(signalController, (Track) nextTrack);
          nextTrack.addSignalController(signalController);
        }
      }
    }
  }

  private void setStationSignals() {
    MapGraph graph = map.getGraph();
    Set<Station> stations = map.getStations().values();
    for (Station station : stations) {
      logger.warn("setting signals for station {}", station);
      Track nextTrack = graph.getChildren(station).stream().map(Optional::ofNullable)
          .findFirst().flatMap(Function.identity()).map(c -> (Track) c).orElse(null);
      Track previousTrack = graph.getParents(station).stream().map(Optional::ofNullable)
          .findFirst().flatMap(Function.identity()).map(c -> (Track) c).orElse(null);

      if (nextTrack != null) {
        SignalController signalControllerNext = nextTrack.getSignalController();
        if (signalControllerNext != null) {
          throw new IllegalStateException("no idea how this can happen");
        }

        SignalController signalController = new SignalController(nextTrack);

        SignalUnit mainSignal = signalController.getMainSignal();
        nextTrack.addBlockSignal(mainSignal, 0);
        if (MapBuilderHelper.trackHasTrain(map, nextTrack)) {
          signalController.setStatus(SignalType.RED);
        }
        station.setNextBlockSignalController(signalController, nextTrack);
        nextTrack.addSignalController(signalController);
        logger.warn("added new signal controller");
      }

      if (previousTrack != null) {
        //TODO: probably install a balise so that the train slows down
      }
    }
  }

  private void setBlockSignals() {
    MapGraph graph = map.getGraph();
    Set<Connectable> roots = graph.getRootConnectables();
/*    if (roots.stream().iterator().next() instanceof Track) {
      logger.error("graph: {}", graph.getConnectablesGraph());
    }*/
    logger.warn("roots: {}", roots);
    for (Connectable root : roots) {
      Track track = graph.getFirstTrack(root);
      logger.info("root: Track-{}", map.getTrackID(track));
      addBlockSignalsOnPath(graph, track);
    }
  }

  private void addBlockSignalsOnPath(MapGraph graph, Track track) {

    if (track == null) {
      logger.info("track was null");
      return;
    }
    logger.warn("trying to add Blocksignal on track {}", map.getTrackID(((Track) track)));
    Connectable nextTrack = graph.getChildren(track).stream()
        .map(Optional::ofNullable).findFirst().flatMap(Function.identity()).orElse(null);
    if (nextTrack instanceof Track) {
      logger.warn("Next track: track {}", map.getTrackID(((Track) nextTrack)));
    }
    if (nextTrack == null) {
      logger.info("next-track was null");
      return;
    }
    if (!(nextTrack instanceof Track)) {
      graph.getChildren(nextTrack)
          .forEach(connectable -> addBlockSignalsOnPath(graph, graph.getFirstTrack(connectable)));
      logger.info("next-track was not a track");
      return;
    }
    if (!((Track) nextTrack).getBlockSignals().isEmpty()) {
      logger.info("next-track had block signals");
      return;
    }

    SignalController signalController = new SignalController(nextTrack);

    SignalUnit mainSignal = signalController.getMainSignal();
    SignalUnit distantSignal = signalController.newDistantSignal();

    if (track.getLength() > BRAKE_DISTANCE) {
      int sectionIndexForDistantSignal = (int) (track.getLength() - BRAKE_DISTANCE - 1);
      track.addBlockSignal(distantSignal, sectionIndexForDistantSignal);
      logger.info("added distance signal {} on section {} on track {}", distantSignal,
          sectionIndexForDistantSignal, map.getTrackID(((Track) track)));
    } else {
      throw new IllegalArgumentException("Track is not long enough for placing distant signals");
      //logger.error("Track-{} is not long enough for placing distant signals",map.getTrackID(track));
    }
    ((Track) nextTrack).addBlockSignal(mainSignal, 0);
    ((Track) nextTrack).addSignalController(signalController);

    if (MapBuilderHelper.trackHasTrain(map, ((Track) nextTrack))) {
      signalController.setStatus(SignalType.RED);
      logger.warn("set track-{}'s signal controller status to RED",
          map.getTrackID(((Track) nextTrack)));
    } else {
      logger.warn("track-{} doesn't have a train", map.getTrackID(((Track) nextTrack)));
    }
    addBlockSignalsOnPath(graph, (Track) nextTrack);
  }

  private void setSwitchSignals() {
    //TODO
  }

  private void setTrainsAtStations() {
    for (Journey journey : map.getJourneys().values()) {
      if (journey.isDirectionForward()) {
        Connectable firstConnectable = Iterables.getFirst(journey.getJourneyPath().getPath(), null);
        if (firstConnectable instanceof Station) {
          ((Station) firstConnectable).enteredTrain(journey.getTrain());
          journey.getTrain().getEngine().setObjective(TrainObjective.STOP);
        }
      } else {
        Connectable lastConnectable = Iterables.getLast(journey.getJourneyPath().getPath(), null);
        if (lastConnectable instanceof Station) {
          ((Station) lastConnectable).enteredTrain(journey.getTrain());
          journey.getTrain().getEngine().setObjective(TrainObjective.STOP);
        }
      }
    }
  }

  private void createStations(Map<String, Object> stations) {
    if (stations == null) {
      return;
    }
    for (Map.Entry<String, Object> station : stations.entrySet()) {
      int stationID = Integer.parseInt(station.getKey());
      Map<String, Integer> stationData = (Map<String, Integer>) station.getValue();
      Station s = new Station(stationData.get("capacity"), stationData.get("wait"));

      map.addStation(stationID, s);
    }
  }

  private void createJourneys(Map<String, Object> journeys) {
    if (journeys == null) {
      return;
    }
    Set<Integer> allocatedTrains = new HashSet<>();
    for (Map.Entry<String, Object> j : journeys.entrySet()) {
      int journeyID = Integer.parseInt(j.getKey());
      Map<String, Object> journeyData = (Map<String, Object>) j.getValue();

      int jpID = (int) journeyData.get("path");
      boolean isForward = (boolean) journeyData.getOrDefault("isForward", true);

      if (journeyID <= 0) {
        if ((int) journeyData.get("repeat") != 1) {
          throw new IllegalStateException("Special feature not yet implemented");
        }

        int numJourneys = map.getJourneys().keySet().size();
        double ratio = (double) journeyData.getOrDefault("ratio", 1.0);
        int limit = (int) Math.round(map.getTrains().keySet().size() * ratio);

        for (int trainID : map.getTrains().keySet()) {
          if (allocatedTrains.contains(trainID)) {
            continue;
          }
          if (limit-- <= 0) {
            break;
          }

          allocatedTrains.add(trainID);
          logger.debug("numJourneys {}, jpID {}, trainID {}, isForward {}", numJourneys, jpID,
              trainID, isForward);
          map.addJourney(++numJourneys, jpID, trainID, isForward);
        }
      } else {
        int trainID = (int) journeyData.get("train");
        map.addJourney(journeyID, jpID, trainID, isForward);
      }
    }
  }

  private void createJourneyPaths(Map<String, Object> journeyPaths) {
    if (journeyPaths == null) {
      return;
    }
    for (Map.Entry<String, Object> journeyPath : journeyPaths.entrySet()) {
      int journeyPathID = Integer.parseInt(journeyPath.getKey());
      Map<String, Object> jData = (Map<String, Object>) journeyPath.getValue();
      List<Map<String, Object>> path = (List<Map<String, Object>>) jData.get("path");
      List<Connectable> connectables = new ArrayList<>();
      for (Map<String, Object> connectable : path) {
        int connectableID = (int) connectable.get("id");
        String connectableType = (String) connectable.get("type");
        switch (connectableType) {
          case "track":
            connectables.add(map.getTrack(connectableID));
            break;
          case "switch":
            connectables.add(map.getSwitch(connectableID));
            break;
          case "station":
            connectables.add(map.getStation(connectableID));
            break;
          default:
            throw new IllegalArgumentException(
                "Invalid journeyPath path-element type: " + connectableType);
        }
      }
      map.addJourneyPath(journeyPathID, connectables,
          (boolean) jData.getOrDefault("isDual", false));
    }
  }

  private void createSwitches(Map<String, Object> switches) {
    if (switches == null) {
      return;
    }
    for (Map.Entry<String, Object> s : switches.entrySet()) {
      int switchID = Integer.parseInt(s.getKey());
      Map<String, Object> sData = (Map<String, Object>) s.getValue();
      List<Integer> left = (List<Integer>) sData.get("left");
      List<Integer> right = (List<Integer>) sData.get("right");

      int statusLeft = (int) sData.get("statusLeft");
      int statusRight = (int) sData.get("statusRight");

      map.addSwitch(switchID, left, right, statusLeft, statusRight);
    }
  }

  private void createTracks(Map<String, Object> trackMap) {
    if (trackMap == null) {
      return;
    }
    for (Map.Entry<String, Object> track : trackMap.entrySet()) {
      int trackID = Integer.parseInt(track.getKey());
      Map<String, Object> trackData = (Map<String, Object>) track.getValue();
      Track t = new Track((Integer) trackData.get("numSections"));

      double aCoeff = (double) trackData.getOrDefault("acceleration", ACCELERATION_COEFFICIENT);
      double dCoeff = (double) trackData.getOrDefault("deceleration", DECELERATION_COEFFICIENT);
      t.setLineCondition(new LineCondition(aCoeff, dCoeff));

      map.addTrack(trackID, t);
      map.registerSectionsForTrack(t.getSections(), trackID);

      int pairID = (int) trackData.getOrDefault("pairID", 0);
      if (pairID > 0) {
        map.addTrackPair(trackID, pairID);
      }
    }
  }

  private void createPlaceables(Map<String, Object> placeablesMap) {
    if (placeablesMap == null) {
      return;
    }
    for (Map.Entry<String, Object> placeable : placeablesMap.entrySet()) {
      int placeableID = Integer.parseInt(placeable.getKey());
      if (placeableID == 0 || map.getPlaceablesMap().get(placeableID) != null) {
        placeableID = map.getPlaceablesMap().keySet().size() + 1;
      }
      Map<String, Object> placeableData = (Map<String, Object>) placeable.getValue();
      Placeable p = null;
      String placeableType = (String) placeableData.getOrDefault("type", "fixedBalise");
      if (placeableType.equals("fixedBalise")) {
        p = new PassiveBalise(Double.valueOf((int) placeableData.get("advisorySpeed")),
            placeableID);
      } else if (placeableType.equals("obstacle")) {
        p = new Obstacle();
      }
      Map<String, Integer> placeOnMap = ((Map<String, Integer>) placeableData.get("placeOn"));
      int trackID = placeOnMap.get("track");
      int section = placeOnMap.get("section");

      map.addPlaceable(placeableID, p, trackID, section);
      assert (p != null) : "Placeable should not be null";
    }
  }

  private void createActiveBalises() {
    int baliseDistance = (int) map.getConfiguration("ferromone_distance");
    int baliseFailure = (int) map.getConfiguration("gsm_failure_rate");

    MapGraph graph = map.getGraph();
    Set<Connectable> roots = graph.getRootConnectables();

    for (Connectable root : roots) {
      Iterator<Connectable> mapIterator = graph.getIterator(root);
      double length = 0;
      while (mapIterator.hasNext()) {
        Connectable c = mapIterator.next();

        if (c instanceof Track && c.getLength() > baliseDistance) {
          Track track = (Track) c;
          if (DualLineHelper.isTrackPairActiveBaliseInitialised(map, track)) {
            length += MapBuilderHelper
                .copyActiveBalises(DualLineHelper.getTrackPair(map, track), track);
          } else {
            boolean isBroken =
                baliseFailure > 0 && Disruptor.getInstance(map).shouldDisrupt(baliseFailure);
            length = placeActiveBalisesOnTrack(length, track, baliseDistance, isBroken);
          }
        }
        length += c.getLength();
      }
    }
  }

  private double placeActiveBalisesOnTrack(double length, Track track, int baliseDistance,
      boolean isBroken) {
    for (double d = 0; d < track.getLength(); d += baliseDistance) {
      if (length == 0) {
        length += baliseDistance;
        continue;
      }
      Placeable balise = new ActiveBalise();
      if (isBroken) {
        ((Disruptable) balise).setIsBroken(true);
      }
      track.placePlaceableOnSectionIndex(balise, (int) d);
      logger.debug("Palced active balise on track {} position {}", map.getTrackID(track), (int) d);
      length += baliseDistance;
    }
    return length;
  }

  private void createTrains(Map<String, Object> trains) {
    if (trains == null) {
      return;
    }
    for (Map.Entry<String, Object> train : trains.entrySet()) {
      int trainID = Integer.parseInt(train.getKey());
      Map<String, Object> trainData = (Map<String, Object>) train.getValue();
      int numCars = (int) trainData.get("numCars");

      if (trainID < 0) { // Special feature
        int numTrains = map.getTrains().keySet().size();
        int count = (int) trainData.get("count");
        for (int i = 0; i < count; i++) {
          Train t = new Train(numCars);
          map.addTrain(++numTrains, t);
        }
      } else {
        Train t = new Train(numCars);
        map.addTrain(trainID, t);
        //t.setTrainID(trainID);
      }
    }
  }

  private void setConfigurations(Map<String, Object> configurations) {
    if (configurations == null) {
      return;
    }
    for (Map.Entry<String, Object> config : configurations.entrySet()) {
      map.addConfiguration(config.getKey(), config.getValue());
    }
  }
}
