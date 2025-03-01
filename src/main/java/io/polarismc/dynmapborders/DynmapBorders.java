package io.polarismc.dynmapborders;

import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.*;
import org.geotools.data.*;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class DynmapBorders extends JavaPlugin {

    private DynmapAPI api;
    private MarkerAPI markerapi;

    FileConfiguration cfg;

    @Override
    public void onEnable() {
        /* Get dynmap */
        Plugin dynmap = getServer().getPluginManager().getPlugin("dynmap");

        if (!(dynmap instanceof DynmapAPI)) {
            this.getLogger().warning("Dynmap not found");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.getLogger().info("Loading dynmap API");
        this.api = (DynmapAPI) dynmap; /* Get API */

        /* If both enabled, activate */
        if (dynmap.isEnabled() && api != null) {
            this.getLogger().info("Dynmap API is enabled and active.");
            try {
                this.getLogger().info("Running Active()");
                activate();
            } catch (IOException e) {
                e.printStackTrace();
                getServer().getPluginManager().disablePlugin(this);
            }
        } else {
            this.getLogger().info("Dynmap API is not enabled or active.");
        }
    }

    /**
     * Used to load config.yml and various resources (shapefile related files, countries.txt)
     * @param resource file path
     * @return File
     */
    public File loadResource(String resource) {
        File folder = getDataFolder();
        if (!folder.exists()) {
            if (!folder.mkdir()) {
                this.getLogger().warning("Resource " + resource + " could not be loaded. Data folder could not be made");
                return null;
            }
        }
        File resourceFile = new File(folder, resource);
        try {
            // if file not already there, it is loaded from the jar
            // if it is not in the jar, give up
            if (!resourceFile.exists()) {
                if (!resourceFile.createNewFile()) {
                    this.getLogger().warning("Resource " + resource + " could not be created");
                    return null;
                }
                try (InputStream in = this.getResource(resource);
                     OutputStream out = new FileOutputStream(resourceFile)) {
                    if (in == null || out == null) {
                        this.getLogger().warning("Resource " + resource + " could not be located");
                        return resourceFile;
                    }
                    ByteStreams.copy(in, out);
                }
                // I don't think this section of code is ever reached. But doesn't hurt to leave it in
                if (!resourceFile.isFile() || resourceFile.length() == 0) {
                    resourceFile.delete();
                    this.getLogger().warning("Resource " + resource + " was not found");
                    return resourceFile;
                }
            }
        } catch (Exception e) { // file stuff always can have exceptions. stay safe
            e.printStackTrace();
        }
        return resourceFile;
    }

    /**
     * Handles everything
     * @throws IOException if something happens
     */
    private void activate() throws IOException {
        try {
            this.getLogger().info("Loading Dynmap API");
            this.markerapi = api.getMarkerAPI();
        } catch (NullPointerException e) { // api is not null, it accesses some object I cant access that is null, which causes this
            this.getLogger().severe("Error loading Dynmap Marker API! Is Dynmap disabled?");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.getLogger().info("Loading config.yml");
        File configFile = this.loadResource("config.yml");
        if (configFile == null) {
            return;
        }
        this.cfg = YamlConfiguration.loadConfiguration(configFile);

        for (String section : cfg.getConfigurationSection("layers").getKeys(false)) {
            // Create new borders markerset
            String layersName = cfg.getString("layers." + section + "." + "layerName", "Borders");
            MarkerSet markerSet = markerapi.getMarkerSet("borders." + layersName);
            if(markerSet == null) {
                markerSet = markerapi.createMarkerSet("borders." + layersName, layersName, null, false);
            } else {
                markerSet.setMarkerSetLabel(layersName);
            }
            if (markerSet == null) {
                this.getLogger().severe("Error creating marker set");
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            }

            markerSet.setLayerPriority(cfg.getInt("layers." + section + ".priority", 12));
            markerSet.setHideByDefault(cfg.getBoolean("layers." + section + ".hideByDefault", true));
            markerSet.setMinZoom(cfg.getInt("layers." + section + ".minimumZoom", -1));
            markerSet.setMaxZoom(cfg.getInt("layers." + section + ".maximumZoom", -1));
        }

        for (String section : cfg.getConfigurationSection("shapefiles").getKeys(false)) {
            section = "shapefiles." + section;
            String fileName = cfg.getString(section + "." + "shapefileName", "countryborders");
            double scaling = 120000 / cfg.getDouble(section + "." + "scaling", 1000);
            double xOffset = cfg.getDouble(section + "." + "xOffset", 0);
            double yMarker = cfg.getInt(section + "." + "y", 64);
            double zOffset = cfg.getDouble(section + "." + "zOffset", 0);

            String desc = cfg.getString(section + "." + "description", null);
            if (desc != null && desc.equalsIgnoreCase("none")) {
                desc = null;
            }

            String layerName = cfg.getString(section + "." + "layer", "Borders");
            MarkerSet markerSet = markerapi.getMarkerSet("borders." + layerName);
            if (markerSet == null) {
                this.getLogger().warning("Layer \"" + layerName + "\" not found! "
                        + "Shapefile \"" + fileName + "\" not added.");
                continue;
            }

            // get min/max zoom for indiv layers
            int minZoomForShape = cfg.getInt(section + "." + "minimumZoom", -1);
            if (minZoomForShape == -1) minZoomForShape = markerSet.getMinZoom();
            int maxZoomForShape = cfg.getInt(section + "." + "maximumZoom", -1);
            if (maxZoomForShape == -1) maxZoomForShape = markerSet.getMaxZoom();

            boolean errors = false;

            File shapefile = new File(this.getDataFolder(), fileName + ".shp");
            if (shapefile == null || !shapefile.isFile()) {
                shapefile = this.loadResource(fileName + ".shp");
                if (!shapefile.isFile() || shapefile.length() == 0) {
                    this.getLogger().warning("Shapefile " + fileName + " not found!");
                    shapefile.delete();
                    continue;
                } else {
                    File shx = this.loadResource(fileName + ".shx");
                    File prj = this.loadResource(fileName + ".prj");
                    File dbf = this.loadResource(fileName + ".dbf");
                    List<File> additionalFiles = List.of(shx, prj, dbf);

                    boolean exit = false;
                    for (File file : additionalFiles) {
                        if (!file.isFile() || file.length() == 0) {
                            this.getLogger().warning(file.getName() + " not found! ");
                            file.delete();
                            exit = true;
                            continue;
                        }
                    }
                    if (exit) {
                        this.getLogger().warning("One or more additional files could not be located for shapefile " + fileName + ".shp");
                        this.getLogger().warning("Shapefile" + fileName + ".shp not loaded!");
                        continue;
                    }
                }
            }

            FileDataStore store = FileDataStoreFinder.getDataStore(shapefile);

            SimpleFeatureSource featureSource = store.getFeatureSource();

            World world = this.getServer().getWorld(cfg.getString(section + "." + "world"));
            if (world == null) {
                this.getLogger().severe("No world found!");
                store.dispose();
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            }

            if (featureSource.getSchema() == null || featureSource.getSchema().getCoordinateReferenceSystem() == null) {
                this.getLogger().warning("Could not load .prj file for Shapefile " + fileName + ".");
                store.dispose();
                continue;
            }
            CoordinateReferenceSystem data = featureSource.getSchema().getCoordinateReferenceSystem();
            String code = data.getName().getCode();
            this.getLogger().info("Shapefile format: " + code);
            if (!code.contains("WGS_1984") && !code.contains("wgs_1984")) {
                this.getLogger().info("Translating " + fileName + " to a readable format...");
                try {
                    this.translateCRS(featureSource, shapefile);
                    this.getLogger().info("Translating finished.");
                } catch (FactoryException e) {
                    e.printStackTrace();
                    store.dispose();
                    continue;
                }
            }

            FeatureCollection<SimpleFeatureType, SimpleFeature> features;
            try {
                features = featureSource.getFeatures();
            } catch (IOException e) {
                e.printStackTrace();
                store.dispose();
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            }

            int iteration = -1;
            try (FeatureIterator<SimpleFeature> iterator = features.features()) {
                while (iterator.hasNext()) {
                    iteration++;
                    SimpleFeature feature = iterator.next();

                    int index = 0;

                    for (Property property : feature.getProperties()) {
                        index++;
                        if (property.getValue() == null || property.getValue().toString() == null) {
                            errors = true;
                            continue;
                        }
                        String propertyValue = property.getValue().toString();
                        if (!propertyValue.contains("((")) continue;

                        String[] polygons = { propertyValue };
                        if (propertyValue.contains("), (")) {
                            polygons = propertyValue.split(Pattern.quote("), ("));
                        }

                        int polygonIndex = 0;
                        for (String polygon : polygons) {
                            String id = section +  "_" + iteration + "_" + index + "_" + polygonIndex;
                            polygon = polygon.replace("MULTIPOLYGON ", "").replace("(", "").replace(")", "");
                            String[] locations = polygon.split(", ");

                            double[] x = new double[locations.length];
                            double[] y = new double[locations.length];
                            double[] z = new double[locations.length];

                            int i = 0;
                            for (String location : locations) {
                                String[] coords = location.split(" ");
                                double lat = 0;
                                double lon = 0;
                                try {
                                    lat = Double.valueOf(coords[0]);
                                    lon = Double.valueOf(coords[1]);
                                } catch (NumberFormatException e) {
                                    e.printStackTrace();
                                    continue;
                                }
                                if (lat > 180 || lon + 90 > 180) {
                                    errors = true;
                                }
                                x[i] = (lat * scaling) + xOffset;

                                y[i] = yMarker;

                                z[i] = (lon * scaling) * -1 + zOffset;
                                i++;
                            }

                            if (markerSet.findPolyLineMarker(id) != null) markerSet.findPolyLineMarker(id).deleteMarker();

                            PolyLineMarker polyline = markerSet.createPolyLineMarker(id,
                                    "", false, world.getName(), x, y, z, false);
                            if(polyline == null) {
                                this.getLogger().info("Error adding polyline " + id);
                                continue;
                            }

                            int color = cfg.getInt(section + "." + "style.color", 0xCC66CC);
                            polyline.setLineStyle(cfg.getInt(section + "." + "style.thickness", 3),
                                    cfg.getDouble(section + "." + "style.opacity", .5), color);
                            if (minZoomForShape > -1) polyline.setMinZoom(minZoomForShape);
                            if (maxZoomForShape > -1) polyline.setMaxZoom(maxZoomForShape);
                            if (desc != null) polyline.setLabel(desc);

                            polygonIndex++;
                        }
                    }
                }
            } catch (Exception e) { // can happen from a bad cast or something
                store.dispose();
                e.printStackTrace();
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            } finally {
                store.dispose();
            }
            if (errors) {
                this.getLogger().warning("Shapefile " + fileName + " had errors on load and may be partially" +
                        " or completely unloaded. Shapefile is likely incorrectly formatted, or goes outside of normal borders.");
            } else {
                this.getLogger().info("Shapefile " + fileName + " successfully loaded!");
            }
        }

        if (cfg.getBoolean("countryMarkers.enable", true)) {
            handleCountryMarkers();
        }

        this.getLogger().info("Version " + this.getDescription().getVersion() + " is activated!");
    }

    /**
     * Handles country markers
     * Only run after activate()
     * @throws IOException
     */
    public void handleCountryMarkers() throws IOException {
        File countriesSetFile = this.loadResource("countries.txt"); // just loading it into the plugin dir so it can be accessed
        FileReader reader = new FileReader(countriesSetFile);
        if (reader == null) {
            this.getLogger().warning("Countries file not found. Country markers not loaded.");
            return;
        }

        String worldName = cfg.getString("countryMarkers.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            this.getLogger().warning("World name for country markers is null! Country markers not loaded.");
            return;
        }

        String layerName = cfg.getString("countryMarkers.layer", "Borders");
        MarkerSet markerSet = markerapi.getMarkerSet("borders." + layerName);
        if (markerSet == null) {
            this.getLogger().warning("Layer \"" + layerName + "\" not found! Country markers not made.");
            return;
        }

        int minZoom = cfg.getInt("countryMarkers.minimumZoom", -1);
        if (minZoom == -1) minZoom = cfg.getInt("minimumZoom", -1);
        int maxZoom = cfg.getInt("countryMarkers.maximumZoom", -1);
        if (maxZoom == -1) maxZoom = cfg.getInt("maximumZoom", -1);

        double scaling = 120000 / cfg.getDouble("countryMarkers.scaling", 1000);

        for (String string : CharStreams.readLines(reader)) {
            String[] separated = string.split("\t");

            double xOffset = cfg.getDouble("countryMarkers.xOffset", 0);
            double zOffset = cfg.getDouble("countryMarkers.zOffset", 0);

            double x = (Double.valueOf(separated[2]) * scaling) + xOffset;
            double y = cfg.getInt("countryMarkers.y", 64);
            double z = (Double.valueOf(separated[1]) * scaling * -1) + zOffset;

            MarkerIcon markerIcon = markerapi.getMarkerIcon(cfg.getString("countryMarkers.markerIconName", "king"));

            Marker marker = markerSet.createMarker(separated[0], separated[3], world.getName(), x, y, z, markerIcon, false);

            if (minZoom > -1) marker.setMinZoom(minZoom);
            if (maxZoom > -1) marker.setMaxZoom(maxZoom);
        }
        this.getLogger().info("Country markers enabled!");
    }

    public void translateCRS(SimpleFeatureSource featureSource, File shapefile) throws FactoryException, IOException {
        SimpleFeatureType schema = featureSource.getSchema();

        CoordinateReferenceSystem otherCRS = schema.getCoordinateReferenceSystem();
        CoordinateReferenceSystem worldCRS = DefaultGeographicCRS.WGS84;

        MathTransform transform = CRS.findMathTransform(otherCRS, worldCRS, true);
        SimpleFeatureCollection featureCollection = featureSource.getFeatures();

        // how do i file
        File newFile = new File(shapefile.getParent(), shapefile.getName() + "copying.shp");
        newFile.createNewFile();

        DataStoreFactorySpi factory = new ShapefileDataStoreFactory();
        Map<String, Serializable> create = new HashMap<String, Serializable>();
        create.put("url", newFile.toURI().toURL());
        create.put("create spatial index", Boolean.TRUE);
        DataStore dataStore = factory.createNewDataStore(create);
        SimpleFeatureType featureType = SimpleFeatureTypeBuilder.retype(schema, worldCRS);
        dataStore.createSchema(featureType);

        String createdName = dataStore.getTypeNames()[0];

        Transaction transaction = new DefaultTransaction("Reproject");
        FeatureWriter<SimpleFeatureType, SimpleFeature> writer =
                dataStore.getFeatureWriterAppend(createdName, transaction);
        SimpleFeatureIterator iterator = featureCollection.features();
        try {
            while (iterator.hasNext()) {
                // copy the contents of each feature and transform the geometry
                SimpleFeature feature = iterator.next();
                SimpleFeature copy = writer.next();
                copy.setAttributes(feature.getAttributes());

                Geometry geometry = (Geometry) feature.getDefaultGeometry();
                Geometry geometry2 = JTS.transform(geometry, transform);

                copy.setDefaultGeometry(geometry2);
                writer.write();
            }
        } catch (Exception e) {
            e.printStackTrace();
            transaction.rollback();
        } finally {
            writer.close();
            iterator.close();
            transaction.commit();
            transaction.close();
        }

        // kinda sketchy way of deleting the copy files
        File folder = shapefile.getParentFile();
        for (File file : folder.listFiles()) {
            if (file.getName().contains(shapefile.getName() + "copying")) {
                file.delete();
            }
        }
    }

}