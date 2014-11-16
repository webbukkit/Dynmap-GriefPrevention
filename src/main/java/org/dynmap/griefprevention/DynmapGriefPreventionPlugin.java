package org.dynmap.griefprevention;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

public class DynmapGriefPreventionPlugin extends JavaPlugin {

    private static Logger log;
    private static final String DEF_INFOWINDOW = "div class=\"infowindow\">Claim Owner: <span style=\"font-weight:bold;\">%owner%</span><br/>Permission Trust: <span style=\"font-weight:bold;\">%managers%</span><br/>Trust: <span style=\"font-weight:bold;\">%builders%</span><br/>Container Trust: <span style=\"font-weight:bold;\">%containers%</span><br/>Access Trust: <span style=\"font-weight:bold;\">%accessors%</span></div>";
    private static final String DEF_ADMININFOWINDOW = "<div class=\"infowindow\"><span style=\"font-weight:bold;\">Administrator Claim</span><br/>Permission Trust: <span style=\"font-weight:bold;\">%managers%</span><br/>Trust: <span style=\"font-weight:bold;\">%builders%</span><br/>Container Trust: <span style=\"font-weight:bold;\">%containers%</span><br/>Access Trust: <span style=\"font-weight:bold;\">%accessors%</span></div>";
    private static final String ADMIN_ID = "administrator";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    GriefPrevention gp;
    MarkerSet set;
    long updperiod;
    boolean use3d;
    String infowindow;
    String admininfowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> ownerstyle;
    Set<String> visible;
    Set<String> hidden;
    boolean stop;
    int maxdepth;

    @Override
    public void onLoad() {
        log = this.getLogger();
    }

    private static class AreaStyle {

        String strokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        String label;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path + ".strokeColor", def.strokecolor);
            strokeopacity = cfg.getDouble(path + ".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path + ".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path + ".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path + ".fillOpacity", def.fillopacity);
            label = cfg.getString(path + ".label", null);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path + ".strokeColor", "#FF0000");
            strokeopacity = cfg.getDouble(path + ".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path + ".strokeWeight", 3);
            fillcolor = cfg.getString(path + ".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path + ".fillOpacity", 0.35);
        }
    }

    public static void info(String msg) {
        log.log(Level.INFO, msg);
    }

    public static void severe(String msg) {
        log.log(Level.SEVERE, msg);
    }

    private class GriefPreventionUpdate implements Runnable {

        boolean repeat = true;

        @Override
        public void run() {
            if (!stop) {
                updateClaims();
                if (repeat) {
                    getServer().getScheduler().scheduleSyncDelayedTask(DynmapGriefPreventionPlugin.this, new GriefPreventionUpdate(), updperiod);
                }
            }
        }
    }

    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();

    private String formatInfoWindow(Claim claim, AreaMarker m) {
        String v;
        if (claim.isAdminClaim()) {
            v = "<div class=\"regioninfo\">" + admininfowindow + "</div>";
        } else {
            v = "<div class=\"regioninfo\">" + infowindow + "</div>";
        }
        v = v.replace("%owner%", claim.isAdminClaim() ? ADMIN_ID : claim.getOwnerName());
        v = v.replace("%area%", Integer.toString(claim.getArea()));
        ArrayList<String> builders = new ArrayList<String>();
        ArrayList<String> containers = new ArrayList<String>();
        ArrayList<String> accessors = new ArrayList<String>();
        ArrayList<String> managers = new ArrayList<String>();
        claim.getPermissions(builders, containers, accessors, managers);
        /* Build builders list */
        String accum = "";
        for (int i = 0; i < builders.size(); i++) {
            if (i > 0) {
                accum += ", ";
            }
            accum += builders.get(i);
        }
        v = v.replace("%builders%", accum);
        /* Build containers list */
        accum = "";
        for (int i = 0; i < containers.size(); i++) {
            if (i > 0) {
                accum += ", ";
            }
            accum += containers.get(i);
        }
        v = v.replace("%containers%", accum);
        /* Build accessors list */
        accum = "";
        for (int i = 0; i < accessors.size(); i++) {
            if (i > 0) {
                accum += ", ";
            }
            accum += accessors.get(i);
        }
        v = v.replace("%accessors%", accum);
        /* Build managers list */
        accum = "";
        for (int i = 0; i < managers.size(); i++) {
            if (i > 0) {
                accum += ", ";
            }
            accum += managers.get(i);
        }
        v = v.replace("%managers%", accum);

        return v;
    }

    private boolean isVisible(String owner, String worldname) {
        if ((visible != null) && (visible.size() > 0)) {
            if ((visible.contains(owner) == false) && (visible.contains("world:" + worldname) == false)
                    && (visible.contains(worldname + "/" + owner) == false)) {
                return false;
            }
        }
        if ((hidden != null) && (hidden.size() > 0)) {
            if (hidden.contains(owner) || hidden.contains("world:" + worldname) || hidden.contains(worldname + "/" + owner)) {
                return false;
            }
        }
        return true;
    }

    private void addStyle(String owner, String worldid, AreaMarker m, Claim claim) {
        AreaStyle as = null;

        if (!ownerstyle.isEmpty()) {
            as = ownerstyle.get(owner.toLowerCase());
        }
        if (as == null) {
            as = defstyle;
        }

        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if (as.label != null) {
            m.setLabel(as.label);
        }
    }

    /* Handle specific claim */
    private void handleClaim(Claim claim, Map<String, AreaMarker> newmap) {
        double[] x;
        double[] z;
        Location l0 = claim.getLesserBoundaryCorner();
        Location l1 = claim.getGreaterBoundaryCorner();
        if (l0 == null) {
            return;
        }
        String wname = l0.getWorld().getName();
        String owner = claim.isAdminClaim() ? ADMIN_ID : claim.getOwnerName();
        /* Handle areas */
        if (isVisible(owner, wname)) {
            /* Make outline */
            x = new double[4];
            z = new double[4];
            x[0] = l0.getX();
            z[0] = l0.getZ();
            x[1] = l0.getX();
            z[1] = l1.getZ() + 1.0;
            x[2] = l1.getX() + 1.0;
            z[2] = l1.getZ() + 1.0;
            x[3] = l1.getX() + 1.0;
            z[3] = l0.getZ();
            UUID uuid = claim.ownerID;
            String markerid = "GP_" + Long.toHexString(uuid.getMostSignificantBits()) + Long.toHexString(uuid.getLeastSignificantBits());
            AreaMarker m = resareas.remove(markerid); /* Existing area? */

            if (m == null) {
                m = set.createAreaMarker(markerid, owner, false, wname, x, z, false);
                if (m == null) {
                    return;
                }
            } else {
                m.setCornerLocations(x, z); /* Replace corner locations */

                m.setLabel(owner);   /* Update label */

            }
            if (use3d) { /* If 3D? */

                m.setRangeY(l1.getY() + 1.0, l0.getY());
            }
            /* Set line and fill properties */
            addStyle(owner, wname, m, claim);

            /* Build popup */
            String desc = formatInfoWindow(claim, m);

            m.setDescription(desc); /* Set popup */

            /* Add to map */
            newmap.put(markerid, m);
        }
    }

    /* Update grief prevention region information */
    @SuppressWarnings("unchecked")
    private void updateClaims() {
        Map<String, AreaMarker> newmap = new HashMap<String, AreaMarker>(); /* Build new map */

        DataStore ds = gp.dataStore;

        ArrayList<Claim> claims = null;
        try {
            Field fld = DataStore.class.getDeclaredField("claims");
            fld.setAccessible(true);
            Object o = fld.get(ds);
            if (o instanceof ArrayList) {
                claims = (ArrayList<Claim>) o;
            }
        } catch (NoSuchFieldException e) {
        } catch (IllegalArgumentException e) {
        } catch (IllegalAccessException e) {
        }
        /* If claims, process them */
        if (claims != null) {
            int sz = claims.size();
            for (int i = 0; i < sz; i++) {
                Claim claim = claims.get(i);
                handleClaim(claim, newmap);
            }
            int idx = sz;
            for (int i = 0; i < sz; i++) {
                Claim claim = claims.get(i);
                if ((claim.children != null) && (claim.children.size() > 0)) {
                    for (Claim children : claim.children) {
                        handleClaim(children, newmap);
                        idx++;
                    }
                }
            }
        }
        /* Now, review old map - anything left is gone */
        for (AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;
    }

    private class OurServerListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if (name.equals("dynmap") || name.equals("GriefPrevention")) {
                if (dynmap.isEnabled() && gp.isEnabled()) {
                    activate();
                }
            }
        }
    }

    @Override
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if (dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI) dynmap; /* Get API */
        /* Get GriefPrevention */

        Plugin p = pm.getPlugin("GriefPrevention");
        if (p == null) {
            severe("Cannot find GriefPrevention!");
            return;
        }
        gp = (GriefPrevention) p;

        getServer().getPluginManager().registerEvents(new OurServerListener(), this);
        /* If both enabled, activate */
        if (dynmap.isEnabled() && gp.isEnabled()) {
            activate();
        }

        try {
            MetricsLite ml = new MetricsLite(this);
            ml.start();
        } catch (IOException iox) {
        }
    }
    private boolean reload = false;

    private void activate() {
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if (markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        if (reload) {
            reloadConfig();
            if (set != null) {
                set.deleteMarkerSet();
                set = null;
            }
            resareas.clear();
        } else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */

        this.saveConfig();  /* Save updates, if needed */

        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("griefprevention.markerset");
        if (set == null) {
            set = markerapi.createMarkerSet("griefprevention.markerset", cfg.getString("layer.name", "GriefPrevention"), null, false);
        } else {
            set.setMarkerSetLabel(cfg.getString("layer.name", "GriefPrevention"));
        }
        if (set == null) {
            severe("Error creating marker set");
            return;
        }
        int minzoom = cfg.getInt("layer.minzoom", 0);
        if (minzoom > 0) {
            set.setMinZoom(minzoom);
        }
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dregions", false);
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        admininfowindow = cfg.getString("adminclaiminfowindow", DEF_ADMININFOWINDOW);
        maxdepth = cfg.getInt("maxdepth", 16);

        /* Get style information */
        defstyle = new AreaStyle(cfg, "regionstyle");
        ownerstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("ownerstyle");
        if (sect != null) {
            Set<String> ids = sect.getKeys(false);

            for (String id : ids) {
                ownerstyle.put(id.toLowerCase(), new AreaStyle(cfg, "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = cfg.getStringList("visibleregions");
        if (vis != null) {
            visible = new HashSet<String>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenregions");
        if (hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if (per < 15) {
            per = 15;
        }
        updperiod = (long) (per * 20);
        stop = false;

        getServer().getScheduler().scheduleSyncDelayedTask(this, new GriefPreventionUpdate(), 40);   /* First time is 2 seconds */

        info("version " + this.getDescription().getVersion() + " is activated");
    }

    @Override
    public void onDisable() {
        if (set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }
}
