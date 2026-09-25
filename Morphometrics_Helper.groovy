// ============================================================
//  Zebrafish morphometrics - Fiji / Groovy
//  Script Editor: Language > Groovy, then Run
// ============================================================

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentListener

import ij.IJ
import ij.Prefs
import ij.gui.Line
import ij.gui.PolygonRoi
import ij.gui.Roi
import ij.gui.WaitForUserDialog
import ij.io.DirectoryChooser
import ij.plugin.frame.RoiManager

// ------------------------------------------------------------
//  config
// ------------------------------------------------------------

Map parseConfig(String path) {
    def cfg = [:]
    new File(path).eachLine { String line ->
        line = line.trim()
        if (line && !line.startsWith('#') && line.contains('=')) {
            int e = line.indexOf('=')
            cfg[line.substring(0, e).trim()] = line.substring(e + 1).trim()
        }
    }
    return cfg
}

void writeConfigTemplate(File f) {
    f.setText('''\
# Morphometrics Helper configuration
# Lines starting with # are comments. Format: key = value

# The views (image orientations) found in this folder, comma-separated.
views = lateral, dorsal

# Order the views repeat in across the sorted image list. Used to predict
# each image's view until it is confirmed. Defaults to the order above.
view_cycle = lateral, dorsal

# Per-view settings, as view.<name>.<key>. A key without the prefix
# (e.g. scale_unit = mm) applies to every view that doesn't set its own.
#   scale_distance_px  scale bar length in pixels. 0 = not set yet; the
#                      Settings window opens so it can be measured on an image.
#   scale_known        real length of that scale bar (default 1)
#   scale_unit         unit of scale_known (default mm)
#   measurements       name[:type] entries separated by ';'
#                      type is length or angle. If omitted, names ending in
#                      _angle are angles and everything else is a length.
#                      Names ending in _width start with a vertical line.
#   position.<name>    where that measurement's line/angle first appears, as
#                      fractions of image width/height. Set from Settings.

view.lateral.scale_distance_px = 0
view.lateral.scale_known = 1
view.lateral.scale_unit = mm
view.lateral.measurements = body_length; head_length; eye_diameter; jaw_angle

view.dorsal.scale_distance_px = 0
view.dorsal.scale_known = 1
view.dorsal.scale_unit = mm
view.dorsal.measurements = head_width; interocular_width; fin_angle

# Optional settings (defaults shown)
# image_extensions = .jpg,.jpeg,.tif,.tiff,.png
# roi_subdir = RoiSets
# output_long = measurements_long.csv
# view_assign = image_views.csv
''', 'UTF-8')
}

List parseMeasurements(String spec) {
    def out = []
    spec.split(';').each { String item ->
        item = item.trim()
        if (!item) return
        def parts = item.split(':').collect { it.trim() }
        String name = parts[0]
        String type
        if (parts.size() > 1 && parts[1]) {
            type = parts[1].equalsIgnoreCase('angle') ? 'angle' : 'length'
        } else {
            type = name.endsWith('_angle') ? 'angle' : 'length'
        }
        out << [name: name, type: type]
    }
    return out
}

/** Builds the list of view specs, accepting both the multi-view and the
 *  older single-view config layouts. */
List parseViews(Map cfg, Closure measParser) {
    def names = []
    if (cfg.views) {
        names = cfg.views.split(',').collect { it.trim() }.findAll { it }
    } else if (cfg.view) {
        names = [cfg.view.trim()]
    }
    if (!names) throw new IllegalArgumentException('config: no views defined')

    def out = []
    names.each { String n ->
        String pfx = 'view.' + n + '.'
        String px = cfg.get(pfx + 'scale_distance_px', cfg.get('scale_distance_px', '0'))
        String kn = cfg.get(pfx + 'scale_known', cfg.get('scale_known', '1'))
        String un = cfg.get(pfx + 'scale_unit', cfg.get('scale_unit', 'mm'))
        String ms = cfg.get(pfx + 'measurements', cfg.get('measurements', ''))

        double pxv = Double.parseDouble(px)
        if (pxv < 0)
            throw new IllegalArgumentException("config: scale_distance_px is negative for view '${n}'")
        def meas = measParser(ms)
        if (!meas)
            throw new IllegalArgumentException("config: no measurements defined for view '${n}'")

        meas.each { Map m ->
            String pos = cfg.get(pfx + 'position.' + m.name)
            if (pos) {
                def v = pos.split(',').collect { Double.parseDouble(it.trim()) }
                if (v.size() == (m.type == 'angle' ? 6 : 4)) m.pos = v
            }
        }

        out << [name: n, scalePx: pxv, scaleKnown: Double.parseDouble(kn),
                unit: un, meas: meas]
    }
    return out
}

// ------------------------------------------------------------
//  minimal CSV
// ------------------------------------------------------------

List<String> splitCsvLine(String line) {
    def out = []
    def cur = new StringBuilder()
    boolean inq = false
    line.each { String ch ->
        if (ch == '"') {
            inq = !inq
        } else if (ch == ',' && !inq) {
            out << cur.toString(); cur = new StringBuilder()
        } else {
            cur.append(ch)
        }
    }
    out << cur.toString()
    return out
}

String csvField(Object v) {
    String s = v == null ? '' : v.toString()
    if (s.contains(',') || s.contains('"') || s.contains('\n')) {
        return '"' + s.replace('"', '""') + '"'
    }
    return s
}

class Store {

    static final List HEADER = ['Folder', 'Image', 'View', 'Structure', 'Type', 'Value']

    String path
    List<Map> rows = []
    Map index = [:]
    Closure splitter
    Closure quoter

    Store(String path, Closure splitter, Closure quoter) {
        this.path = path
        this.splitter = splitter
        this.quoter = quoter
        load()
    }

    private void load() {
        def f = new File(path)
        if (!f.exists()) return
        def lines = f.readLines()
        if (!lines) return
        def hdr = splitter(lines[0])
        lines.drop(1).each { String line ->
            if (!line.trim()) return
            def vals = splitter(line)
            def row = [:]
            hdr.eachWithIndex { String h, int i ->
                row[h] = i < vals.size() ? vals[i] : ''
            }
            rows << row
            index[key(row.Image, row.View, row.Structure)] = row
        }
    }

    private static String key(Object img, Object view, Object structure) {
        return "${img}\u0000${view}\u0000${structure}"
    }

    Map get(String image, String view, String structure) {
        return index[key(image, view, structure)]
    }

    /** Any view name already recorded against this image, or null. */
    String viewRecordedFor(String image) {
        def hit = rows.find { it.Image == image && it.View }
        return hit ? hit.View : null
    }

    void put(String folder, String image, String view, String structure,
             String type, double value) {
        String k = key(image, view, structure)
        def row = index[k]
        if (row == null) {
            row = [:]
            rows << row
            index[k] = row
        }
        row.Folder = folder
        row.Image = image
        row.View = view
        row.Structure = structure
        row.Type = type
        row.Value = String.format('%.5f', value)
        save()
    }

    void remove(String image, String view, String structure) {
        def row = index.remove(key(image, view, structure))
        if (row != null) {
            rows.remove(row)
            save()
        }
    }

    void save() {
        def cols = new ArrayList(HEADER)
        rows.each { Map r -> r.keySet().each { if (!cols.contains(it)) cols << it } }
        def sb = new StringBuilder()
        sb.append(cols.join(',')).append('\n')
        rows.each { Map r ->
            sb.append(cols.collect { quoter(r[it] ?: '') }.join(',')).append('\n')
        }
        new File(path).setText(sb.toString(), 'UTF-8')
    }
}

/** Rewrites keys in the config file in place, keeping comments and layout. */
class ConfigFile {

    /** A null value removes the key. New view.<name>.* keys go after the
     *  last existing line for that view; anything else is appended. */
    static void update(File f, Map<String, String> updates) {
        def seen = [] as Set
        def out = []
        f.readLines('UTF-8').each { String line ->
            String t = line.trim()
            if (t && !t.startsWith('#') && t.contains('=')) {
                String k = t.substring(0, t.indexOf('=')).trim()
                if (updates.containsKey(k)) {
                    seen << k
                    if (updates[k] != null) out << (k + ' = ' + updates[k])
                    return
                }
            }
            out << line
        }
        updates.each { String k, String v ->
            if (k in seen || v == null) return
            int at = -1
            int dot = k.startsWith('view.') ? k.indexOf('.', 5) : -1
            if (dot > 0) {
                String pfx = k.substring(0, dot + 1)
                out.eachWithIndex { String l, int i -> if (l.trim().startsWith(pfx)) at = i }
            }
            if (at >= 0) out.add(at + 1, k + ' = ' + v) else out << (k + ' = ' + v)
        }
        f.setText(out.join('\n') + '\n', 'UTF-8')
    }
}

// ------------------------------------------------------------
//  application
// ------------------------------------------------------------

class App {

    static final String PREF_ON_TOP = 'morphometrics.on_top'
    static final String PREF_LOC = 'morphometrics.location'

    String folder, folderName, cfgPath
    List views                       // list of view spec maps
    Map viewByName = [:]
    List<String> cycle               // expected repeating order
    List<String> images
    String roiDir, assignPath
    Store store

    Map assign = [:]                 // basename -> view name (confirmed)
    Map viewSpec                     // spec of the currently open image
    String curViewName = null

    RoiManager rm
    def imp = null
    int curImg = -1
    Map rois = [:]
    List<Integer> visible = []
    boolean updating = false

    JFrame frame
    JTextField filterField
    JCheckBox hideDone
    JComboBox viewCombo
    JList imageList, measList
    DefaultListModel imageModel, measModel
    JLabel countLabel
    SettingsWindow settings = null

    App(Map cfg, String cfgPath, String folder, Closure splitter, Closure quoter, Closure measParser,
        Closure viewParser) {
        this.folder = folder
        this.cfgPath = cfgPath
        this.folderName = new File(folder).getName()

        this.views = viewParser(cfg, measParser)
        views.each { viewByName[it.name] = it }

        if (cfg.view_cycle) {
            this.cycle = cfg.view_cycle.split(',').collect { it.trim() }
                            .findAll { viewByName.containsKey(it) }
        }
        if (!cycle) this.cycle = views.collect { it.name }

        def exts = cfg.get('image_extensions', '.jpg,.jpeg,.tif,.tiff,.png')
                      .split(',').collect { it.trim().toLowerCase() }.findAll { it }
        this.images = new File(folder).list()
                          .findAll { String n -> exts.any { n.toLowerCase().endsWith(it) } }
                          .sort()
        if (!images) throw new IllegalArgumentException('no images found in ' + folder)

        this.roiDir = new File(folder, cfg.get('roi_subdir', 'RoiSets')).getAbsolutePath()
        new File(roiDir).mkdirs()

        this.store = new Store(
            new File(folder, cfg.get('output_long', 'measurements_long.csv')).getAbsolutePath(),
            splitter, quoter)

        this.assignPath = new File(folder,
            cfg.get('view_assign', 'image_views.csv')).getAbsolutePath()
        loadAssignments(splitter)
        migrateFromStore()

        this.rm = new RoiManager(false)     // hidden scratch manager, zip I/O only

        buildGui()
        refreshImageList()
        if (visible) imageList.setSelectedIndex(0)

        def unset = views.find { it.scalePx <= 0 }
        if (unset) {
            openSettings(unset.name, "No scale is set for view '${unset.name}'. " +
                'Open an image of that view, click "Measure scale bar", draw a line ' +
                'along the scale bar, then click "Use drawn line".')
        }
    }

    // ---------- view assignment ----------

    void loadAssignments(Closure splitter) {
        def f = new File(assignPath)
        if (!f.exists()) return
        def lines = f.readLines()
        if (!lines) return
        def hdr = splitter(lines[0])
        int ci = hdr.indexOf('Image'), cv = hdr.indexOf('View')
        if (ci < 0 || cv < 0) return
        lines.drop(1).each { String line ->
            if (!line.trim()) return
            def v = splitter(line)
            if (v.size() > Math.max(ci, cv) && viewByName.containsKey(v[cv])) {
                assign[v[ci]] = v[cv]
            }
        }
    }

    /** Pre-assign any image that already has measurements recorded, so
     *  existing work is picked up without re-triaging. */
    void migrateFromStore() {
        images.eachWithIndex { String n, int i ->
            String b = basename(i)
            if (assign.containsKey(b)) return
            String v = store.viewRecordedFor(b)
            if (v && viewByName.containsKey(v)) assign[b] = v
        }
        saveAssignments()
    }

    void saveAssignments() {
        def sb = new StringBuilder('Image,View\n')
        images.eachWithIndex { String n, int i ->
            String b = basename(i)
            if (assign.containsKey(b)) sb.append(b).append(',').append(assign[b]).append('\n')
        }
        new File(assignPath).setText(sb.toString(), 'UTF-8')
    }

    /** Confirmed assignment, or a prediction from the expected cycle. */
    String viewNameFor(int i) {
        String b = basename(i)
        if (assign.containsKey(b)) return assign[b]

        // walk back to the nearest confirmed image and step forward in the cycle
        for (int j = i - 1; j >= 0; j--) {
            String pb = basename(j)
            if (assign.containsKey(pb)) {
                int base = cycle.indexOf(assign[pb])
                if (base >= 0) return cycle[(base + (i - j)) % cycle.size()]
                break
            }
        }
        return cycle[i % cycle.size()]
    }

    Map specFor(int i) { return viewByName[viewNameFor(i)] }

    // ---------- helpers ----------

    String basename(int i) {
        String n = images[i]
        int d = n.lastIndexOf('.')
        return d < 0 ? n : n.substring(0, d)
    }

    int nDone(int i) {
        String b = basename(i)
        String v = viewNameFor(i)
        return viewByName[v].meas.count { store.get(b, v, it.name) != null }
    }

    int nTotal(int i) { return viewByName[viewNameFor(i)].meas.size() }

    String roiPath(int i) {
        return new File(roiDir, basename(i) + '_RoiSet.zip').getAbsolutePath()
    }

    // ---------- gui ----------

    void buildGui() {
        frame = new JFrame("Morphometrics  -  ${folderName}")
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)
        frame.addWindowListener(new WindowAdapter() {
            void windowClosing(WindowEvent e) { finish() }
        })

        // ----- left: filterable image list -----
        def left = new JPanel(new BorderLayout())
        left.setBorder(BorderFactory.createTitledBorder('Images'))

        def top = new JPanel(new BorderLayout())
        filterField = new JTextField()
        filterField.getDocument().addDocumentListener([
            insertUpdate : { refreshImageList() },
            removeUpdate : { refreshImageList() },
            changedUpdate: { refreshImageList() }
        ] as DocumentListener)
        top.add(new JLabel('Filter: '), BorderLayout.WEST)
        top.add(filterField, BorderLayout.CENTER)

        hideDone = new JCheckBox('Hide completed', false)
        hideDone.addActionListener { refreshImageList() }
        top.add(hideDone, BorderLayout.SOUTH)
        left.add(top, BorderLayout.NORTH)

        imageModel = new DefaultListModel()
        imageList = new JList(imageModel)
        imageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        imageList.addListSelectionListener { e ->
            if (!e.getValueIsAdjusting()) onImageSelected()
        }
        def sp = new JScrollPane(imageList)
        sp.setPreferredSize(new Dimension(330, 420))
        left.add(sp, BorderLayout.CENTER)

        countLabel = new JLabel(' ')
        left.add(countLabel, BorderLayout.SOUTH)

        // ----- right: view selector, measurements, actions -----
        def right = new JPanel(new BorderLayout())
        right.setBorder(BorderFactory.createTitledBorder('Measurements'))

        def viewPanel = new JPanel(new BorderLayout())
        viewCombo = new JComboBox(views.collect { it.name } as String[])
        viewCombo.addActionListener { onViewChanged() }
        viewPanel.add(new JLabel('View: '), BorderLayout.WEST)
        viewPanel.add(viewCombo, BorderLayout.CENTER)
        right.add(viewPanel, BorderLayout.NORTH)

        measModel = new DefaultListModel()
        measList = new JList(measModel)
        measList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        measList.addListSelectionListener { e ->
            if (!e.getValueIsAdjusting()) onMeasSelected()
        }
        def sp2 = new JScrollPane(measList)
        sp2.setPreferredSize(new Dimension(300, 280))
        right.add(sp2, BorderLayout.CENTER)

        def btns = new JPanel(new GridLayout(0, 2, 4, 4))
        def actions = [
            'Record'          : { record() },
            'Clear'           : { clearCurrent() },
            'Prev measurement': { stepMeas(-1) },
            'Next measurement': { stepMeas(1) },
            'Prev image'      : { stepImage(-1) },
            'Next image'      : { stepImage(1) },
            'Next incomplete' : { nextIncomplete() },
            'Finish session'  : { finish() }
        ]
        actions.each { String label, Closure fn ->
            def b = new JButton(label)
            b.addActionListener { fn() }
            btns.add(b)
        }
        right.add(btns, BorderLayout.SOUTH)

        def main = new JPanel(new BorderLayout(8, 8))
        main.add(left, BorderLayout.WEST)
        main.add(right, BorderLayout.CENTER)

        // ----- top: window options -----
        def onTop = new JCheckBox('Keep this window on top', Prefs.getBoolean(PREF_ON_TOP, true))
        onTop.addActionListener {
            frame.setAlwaysOnTop(onTop.isSelected())
            Prefs.set(PREF_ON_TOP, onTop.isSelected())
        }
        def settingsBtn = new JButton('Settings...')
        settingsBtn.addActionListener { openSettings(null, null) }
        def options = new JPanel(new BorderLayout())
        options.add(onTop, BorderLayout.WEST)
        options.add(settingsBtn, BorderLayout.EAST)
        main.add(options, BorderLayout.NORTH)
        frame.add(main)
        frame.pack()
        placeFrame()
        frame.setAlwaysOnTop(onTop.isSelected())
        frame.setVisible(true)
    }

    /** Last position the operator left the panel at, if still on screen;
     *  otherwise the lower-right corner, clear of the Fiji toolbar. */
    void placeFrame() {
        def screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
        int x = screen.x + screen.width - frame.getWidth() - 10
        int y = screen.y + screen.height - frame.getHeight() - 10
        String saved = Prefs.get(PREF_LOC, null)
        if (saved) {
            def xy = saved.split(',')
            try {
                int sx = Integer.parseInt(xy[0]), sy = Integer.parseInt(xy[1])
                if (screen.contains(sx + 20, sy + 20)) { x = sx; y = sy }
            } catch (Exception ignored) { }
        }
        frame.setLocation(x, y)
    }

    void refreshImageList() {
        String want = filterField.getText().trim().toLowerCase()
        boolean hide = hideDone.isSelected()
        int keepIdx = curImg

        updating = true
        imageModel.clear()
        visible = []
        images.eachWithIndex { String name, int i ->
            if (want && !name.toLowerCase().contains(want)) return
            int done = nDone(i), total = nTotal(i)
            if (hide && done == total && i != keepIdx) return
            visible << i
            String mark = assign.containsKey(basename(i)) ? ' ' : '?'
            imageModel.addElement(String.format('%s  %s%s [%d/%d]',
                name, mark, viewNameFor(i), done, total))
        }
        updating = false

        if (visible.contains(keepIdx)) {
            updating = true
            imageList.setSelectedIndex(visible.indexOf(keepIdx))
            updating = false
        }

        int complete = (0..<images.size()).count { nDone(it) == nTotal(it) }
        countLabel.setText(String.format('  %d shown  -  %d/%d complete  ("?" = view not confirmed)  ',
                                         visible.size(), complete, images.size()))
    }

    void refreshMeasList() {
        int keep = measList.getSelectedIndex()
        String b = curImg >= 0 ? basename(curImg) : ''
        updating = true
        measModel.clear()
        if (viewSpec != null) {
            viewSpec.meas.each { Map m ->
                def row = store.get(b, curViewName, m.name)
                String shown
                if (row == null) {
                    shown = '--'
                } else if (m.type == 'angle') {
                    shown = String.format('%.2f deg', Double.parseDouble(row.Value))
                } else {
                    shown = String.format('%.4f %s', Double.parseDouble(row.Value), viewSpec.unit)
                }
                measModel.addElement(String.format('%-16s  %s', m.name, shown))
            }
        }
        updating = false
        if (keep >= 0 && keep < measModel.getSize()) measList.setSelectedIndex(keep)
    }

    // ---------- image handling ----------

    void onImageSelected() {
        if (updating) return
        int sel = imageList.getSelectedIndex()
        if (sel < 0 || sel >= visible.size()) return
        if (visible[sel] == curImg) return      // already open - don't re-decode
        openImage(visible[sel])
    }

    void openImage(int i) {
        if (imp != null) {
            imp.changes = false
            imp.close()
            imp = null
        }

        curImg = i
        curViewName = viewNameFor(i)
        viewSpec = viewByName[curViewName]

        updating = true
        viewCombo.setSelectedItem(curViewName)
        updating = false

        def newImp = IJ.openImage(new File(folder, images[i]).getAbsolutePath())
        if (newImp == null) {
            IJ.log('Could not open: ' + images[i])
            return
        }
        newImp.show()
        imp = newImp
        applyCalibration()

        rois = loadRois(roiPath(i))

        refreshMeasList()
        if (measModel.getSize() > 0) measList.setSelectedIndex(0)
    }

    /** Named ROIs from a saved RoiSet zip, or empty if there is none. */
    Map loadRois(String path) {
        def out = [:]
        if (!new File(path).exists()) return out
        rm.runCommand('reset')
        rm.runCommand('Open', path)
        rm.getRoisAsArray().each { Roi roi ->
            if (roi.getName()) out[roi.getName()] = roi
        }
        rm.runCommand('reset')
        return out
    }

    void applyCalibration() {
        if (imp == null || viewSpec == null) return
        def cal = imp.getCalibration()
        if (viewSpec.scalePx > 0) {
            cal.pixelWidth = viewSpec.scaleKnown / viewSpec.scalePx
            cal.setUnit(viewSpec.unit)
        } else {
            cal.pixelWidth = 1
            cal.setUnit('pixel')
        }
        cal.pixelHeight = cal.pixelWidth
        imp.getWindow()?.repaint()
    }

    /** Operator changed the view of the current image. */
    void onViewChanged() {
        if (updating || curImg < 0) return
        String chosen = viewCombo.getSelectedItem()
        if (chosen == null || chosen == curViewName) return

        curViewName = chosen
        viewSpec = viewByName[chosen]
        assign[basename(curImg)] = chosen
        saveAssignments()

        applyCalibration()
        if (imp != null) imp.deleteRoi()

        refreshMeasList()
        refreshImageList()
        if (measModel.getSize() > 0) measList.setSelectedIndex(0)
        onMeasSelected()
    }

    void saveRois() {
        if (curImg < 0) return
        String path = roiPath(curImg)
        rm.runCommand('reset')
        if (rois.isEmpty()) {
            def f = new File(path)
            if (f.exists()) f.delete()
            return
        }
        viewSpec.meas.each { Map m ->              // save in config order
            if (rois.containsKey(m.name)) rm.addRoi(rois[m.name])
        }
        rois.each { String n, Roi r ->             // keep anything from another view
            if (!viewSpec.meas.any { it.name == n }) rm.addRoi(r)
        }
        rm.runCommand('Deselect')
        rm.runCommand('Save', path)
        rm.runCommand('reset')
    }

    // ---------- measurement handling ----------

    void onMeasSelected() {
        if (updating || imp == null || viewSpec == null) return
        int m = measList.getSelectedIndex()
        if (m < 0 || m >= viewSpec.meas.size()) return
        def spec = viewSpec.meas[m]
        IJ.setTool(spec.type == 'angle' ? 'angle' : 'line')

        double w = imp.getWidth(), h = imp.getHeight()
        if (rois.containsKey(spec.name)) {
            // a copy, so unrecorded edits on the image never reach the saved set
            imp.setRoi((Roi) rois[spec.name].clone())
        } else if (spec.pos) {
            def p = spec.pos
            if (spec.type == 'angle') {
                float[] xs = [p[0] * w, p[2] * w, p[4] * w] as float[]
                float[] ys = [p[1] * h, p[3] * h, p[5] * h] as float[]
                imp.setRoi(new PolygonRoi(xs, ys, 3, Roi.ANGLE))
            } else {
                imp.setRoi(new Line(p[0] * w, p[1] * h, p[2] * w, p[3] * h))
            }
        } else {
            if (spec.type == 'angle') {
                // vertex on the left, arms opening to the right
                float[] xs = [w * 0.60, w * 0.45, w * 0.60] as float[]
                float[] ys = [h * 0.40, h * 0.50, h * 0.60] as float[]
                imp.setRoi(new PolygonRoi(xs, ys, 3, Roi.ANGLE))
            } else if (spec.name.toLowerCase().endsWith('_width')) {
                imp.setRoi(new Line(w * 0.50, h * 0.40, w * 0.50, h * 0.60))
            } else {
                imp.setRoi(new Line(w * 0.40, h * 0.50, w * 0.60, h * 0.50))
            }
        }
    }

    void record() {
        if (imp == null || viewSpec == null) return
        int m = measList.getSelectedIndex()
        if (m < 0 || m >= viewSpec.meas.size()) return
        def spec = viewSpec.meas[m]
        def roi = imp.getRoi()
        if (roi == null) {
            IJ.showMessage('Nothing selected on the image.')
            return
        }

        double value
        if (spec.type == 'angle') {
            if (roi.getType() != Roi.ANGLE) {
                IJ.showMessage("'${spec.name}' needs an angle selection.")
                return
            }
            value = angleOf(roi)
        } else {
            if (viewSpec.scalePx <= 0) {
                openSettings(curViewName, "Set the scale for view '${curViewName}' " +
                    'before recording lengths.')
                return
            }
            if (roi.getType() != Roi.LINE) {
                IJ.showMessage("'${spec.name}' needs a straight line selection.")
                return
            }
            value = lengthOf(roi)
        }

        roi.setName(spec.name)
        rois[spec.name] = (Roi) roi.clone()

        // recording confirms the view for this image
        assign[basename(curImg)] = curViewName
        saveAssignments()

        store.put(folderName, basename(curImg), curViewName, spec.name, spec.type, value)
        saveRois()

        refreshMeasList()
        refreshImageList()
        if (m + 1 < viewSpec.meas.size()) measList.setSelectedIndex(m + 1)
    }

    void clearCurrent() {
        int m = measList.getSelectedIndex()
        if (m < 0 || curImg < 0 || viewSpec == null) return
        def spec = viewSpec.meas[m]
        rois.remove(spec.name)
        store.remove(basename(curImg), curViewName, spec.name)
        saveRois()
        refreshMeasList()
        refreshImageList()
        onMeasSelected()
    }

    double lengthOf(def roi) {
        def cal = imp.getCalibration()
        double dx = (roi.x2d - roi.x1d) * cal.pixelWidth
        double dy = (roi.y2d - roi.y1d) * cal.pixelHeight
        return Math.sqrt(dx * dx + dy * dy)
    }

    double angleOf(def roi) {
        def p = roi.getFloatPolygon()
        double x1 = p.xpoints[0], y1 = p.ypoints[0]
        double x2 = p.xpoints[1], y2 = p.ypoints[1]      // vertex
        double x3 = p.xpoints[2], y3 = p.ypoints[2]
        double a = Math.atan2(y1 - y2, x1 - x2) - Math.atan2(y3 - y2, x3 - x2)
        double deg = Math.abs(Math.toDegrees(a))
        if (deg > 180) deg = 360 - deg
        return deg
    }

    // ---------- navigation ----------

    void stepMeas(int d) {
        int m = measList.getSelectedIndex() + d
        if (viewSpec != null && m >= viewSpec.meas.size()) {
            stepImage(1)        // past the last measurement: move to the next image
            return
        }
        if (m >= 0) measList.setSelectedIndex(m)
    }

    void stepImage(int d) {
        int s = imageList.getSelectedIndex() + d
        if (s >= 0 && s < visible.size()) imageList.setSelectedIndex(s)
    }

    void nextIncomplete() {
        if (!visible) return
        int start = imageList.getSelectedIndex()
        for (int k = 1; k <= visible.size(); k++) {
            int s = (start + k) % visible.size()
            int i = visible[s]
            if (nDone(i) < nTotal(i)) {
                imageList.setSelectedIndex(s)
                return
            }
        }
        IJ.showMessage('No incomplete images in the current list.')
    }

    // ---------- settings ----------

    void openSettings(String view, String message) {
        if (settings != null) {
            settings.frame.toFront()
            if (message) settings.setHint(message)
            return
        }
        settings = new SettingsWindow(this, view ?: curViewName ?: views[0].name, message)
    }

    /** Called by the settings window after the config has been written. */
    void settingsSaved() {
        applyCalibration()
        refreshMeasList()
        refreshImageList()
    }

    /** Recomputes a view's recorded lengths from their saved line ROIs with
     *  the view's current scale. Returns [updated, without a saved ROI]. */
    List recalcLengths(String view) {
        def spec = viewByName[view]
        int updated = 0, missing = 0
        store.rows.findAll { it.View == view && it.Type == 'length' }
             .groupBy { it.Image }
             .each { String img, List rows ->
                 Map saved = loadRois(new File(roiDir, img + '_RoiSet.zip').getAbsolutePath())
                 rows.each { Map r ->
                     def roi = saved[r.Structure]
                     if (roi != null && roi.getType() == Roi.LINE) {
                         double px = Math.hypot(roi.x2d - roi.x1d, roi.y2d - roi.y1d)
                         r.Value = String.format('%.5f', px * spec.scaleKnown / spec.scalePx)
                         updated++
                     } else {
                         missing++
                     }
                 }
             }
        store.save()
        return [updated, missing]
    }

    void finish() {
        settings?.frame?.dispose()
        store.save()
        saveAssignments()
        if (imp != null) {
            imp.changes = false
            imp.close()
        }
        rm.close()
        Prefs.set(PREF_LOC, frame.getX() + ',' + frame.getY())
        frame.dispose()
        IJ.log('Session ended. Results: ' + store.path)
    }
}

// ------------------------------------------------------------
//  settings window
// ------------------------------------------------------------

/** Per-view scale and default start positions. Edits are held here until
 *  Save, which writes them to the config file and applies them. */
class SettingsWindow {

    App app
    JFrame frame
    JComboBox viewCombo
    JTextField pxField, knownField, unitField
    JButton scaleBtn
    JList posList
    DefaultListModel posModel
    JLabel hint

    Map work = [:]                   // view name -> editable copy
    String shown = null              // view currently in the fields
    boolean measuringScale = false

    SettingsWindow(App app, String view, String message) {
        this.app = app
        app.views.each { Map v ->
            work[v.name] = [px   : num(v.scalePx), known: num(v.scaleKnown), unit: v.unit,
                            pos  : v.meas.collectEntries { [(it.name): it.pos] }]
        }
        build()
        viewCombo.setSelectedItem(view)
        showView(view)
        setHint(message ?: 'Changes apply when you click Save.')
        place()
        frame.setVisible(true)
    }

    static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d)
    }

    static Double parse(String s) {
        try { return Double.parseDouble(s.trim()) } catch (NumberFormatException e) { return null }
    }

    void setHint(String text) {
        String esc = text.replace('&', '&amp;').replace('<', '&lt;')
        hint.setText('<html><body style="width:260px">' + esc + '</body></html>')
    }

    // ---------- gui ----------

    void build() {
        frame = new JFrame('Morphometrics settings')
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
        frame.addWindowListener(new WindowAdapter() {
            void windowClosed(WindowEvent e) { app.settings = null }
        })

        def viewPanel = new JPanel(new BorderLayout())
        viewCombo = new JComboBox(app.views.collect { it.name } as String[])
        viewCombo.addActionListener {
            String v = viewCombo.getSelectedItem()
            if (v != shown) { commit(); showView(v) }
        }
        viewPanel.add(new JLabel('View: '), BorderLayout.WEST)
        viewPanel.add(viewCombo, BorderLayout.CENTER)

        // ----- scale -----
        def scale = new JPanel(new GridLayout(0, 2, 4, 4))
        scale.setBorder(BorderFactory.createTitledBorder('Scale'))
        pxField = new JTextField(8)
        knownField = new JTextField(8)
        unitField = new JTextField(8)
        scale.add(new JLabel('Scale bar length (px)'))
        scale.add(pxField)
        scale.add(new JLabel('Known length'))
        scale.add(knownField)
        scale.add(new JLabel('Unit'))
        scale.add(unitField)
        scale.add(new JLabel(''))
        scaleBtn = new JButton('Measure scale bar')
        scaleBtn.addActionListener { measureScale() }
        scale.add(scaleBtn)

        // ----- default positions -----
        def pos = new JPanel(new BorderLayout(4, 4))
        pos.setBorder(BorderFactory.createTitledBorder('Default start positions'))
        posModel = new DefaultListModel()
        posList = new JList(posModel)
        posList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        posList.addListSelectionListener { e ->
            if (!e.getValueIsAdjusting()) syncMainSelection()
        }
        def sp = new JScrollPane(posList)
        sp.setPreferredSize(new Dimension(300, 140))
        pos.add(sp, BorderLayout.CENTER)
        def posBtns = new JPanel(new GridLayout(1, 2, 4, 4))
        def useSel = new JButton('Use selection on image')
        useSel.addActionListener { takePosition() }
        def reset = new JButton('Reset to standard')
        reset.addActionListener { resetPosition() }
        posBtns.add(useSel)
        posBtns.add(reset)
        pos.add(posBtns, BorderLayout.SOUTH)

        def center = new JPanel()
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS))
        center.add(scale)
        center.add(pos)

        // ----- hint + save/cancel -----
        hint = new JLabel(' ')
        def saveBtn = new JButton('Save')
        saveBtn.addActionListener { save() }
        def cancelBtn = new JButton('Cancel')
        cancelBtn.addActionListener { frame.dispose() }
        def btns = new JPanel(new GridLayout(1, 2, 4, 4))
        btns.add(cancelBtn)
        btns.add(saveBtn)
        def south = new JPanel(new BorderLayout(4, 4))
        south.add(hint, BorderLayout.CENTER)
        south.add(btns, BorderLayout.SOUTH)

        def main = new JPanel(new BorderLayout(8, 8))
        main.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8))
        main.add(viewPanel, BorderLayout.NORTH)
        main.add(center, BorderLayout.CENTER)
        main.add(south, BorderLayout.SOUTH)
        frame.add(main)
        frame.pack()
        frame.setAlwaysOnTop(app.frame.isAlwaysOnTop())
    }

    /** Beside the main window, bottom-aligned with it. */
    void place() {
        def screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
        int x = app.frame.getX() - frame.getWidth() - 10
        if (x < screen.x) x = app.frame.getX() + app.frame.getWidth() + 10
        if (x + frame.getWidth() > screen.x + screen.width) x = screen.x
        int y = app.frame.getY() + app.frame.getHeight() - frame.getHeight()
        int top = screen.y
        frame.setLocation(x, Math.max(top, y))
    }

    // ---------- editing ----------

    void showView(String v) {
        shown = v
        def w = work[v]
        pxField.setText(w.px)
        knownField.setText(w.known)
        unitField.setText(w.unit)
        refreshPositions(-1)
    }

    /** Copies the scale fields back into the working copy. */
    void commit() {
        if (shown == null) return
        def w = work[shown]
        w.px = pxField.getText().trim()
        w.known = knownField.getText().trim()
        w.unit = unitField.getText().trim()
    }

    void refreshPositions(int select) {
        posModel.clear()
        app.viewByName[shown].meas.each { Map m ->
            String state = work[shown].pos[m.name] ? 'custom' : 'standard'
            posModel.addElement(String.format('%-18s %-7s %s', m.name, m.type, state))
        }
        if (select >= 0) posList.setSelectedIndex(select)
    }

    /** Selecting a measurement here selects it in the main window too, so
     *  its line/angle is on the image ready to adjust. */
    void syncMainSelection() {
        int i = posList.getSelectedIndex()
        if (i >= 0 && app.curViewName == shown && i < app.measModel.getSize())
            app.measList.setSelectedIndex(i)
    }

    void measureScale() {
        def imp = app.imp
        if (imp == null) {
            setHint('Open an image first.')
            return
        }
        if (!measuringScale) {
            measuringScale = true
            imp.deleteRoi()
            IJ.setTool('line')
            scaleBtn.setText('Use drawn line')
            setHint('Draw a straight line along the scale bar on the image, ' +
                    'then click "Use drawn line".')
            return
        }
        def roi = imp.getRoi()
        if (roi == null || roi.getType() != Roi.LINE) {
            setHint('No straight line on the image. Draw one along the scale bar, ' +
                    'then click "Use drawn line".')
            return
        }
        measuringScale = false
        scaleBtn.setText('Measure scale bar')
        double px = Math.hypot(roi.x2d - roi.x1d, roi.y2d - roi.y1d)
        pxField.setText(String.format('%.2f', px))
        String note = app.curViewName == shown ? '' :
            " (measured on a '${app.curViewName}' image)"
        setHint("Scale bar is ${String.format('%.2f', px)} px${note}. " +
                'Check its known length and unit, then Save.')
    }

    void takePosition() {
        int i = posList.getSelectedIndex()
        if (i < 0) {
            setHint('Pick a measurement in the list first.')
            return
        }
        def m = app.viewByName[shown].meas[i]
        def imp = app.imp
        def roi = imp?.getRoi()
        boolean ok = roi != null &&
            roi.getType() == (m.type == 'angle' ? Roi.ANGLE : Roi.LINE)
        if (!ok) {
            setHint("Draw ${m.type == 'angle' ? 'an angle' : 'a straight line'} " +
                    "on the image where '${m.name}' should start.")
            return
        }
        double w = imp.getWidth(), h = imp.getHeight()
        List v
        if (m.type == 'angle') {
            def p = roi.getFloatPolygon()
            v = (0..2).collectMany { [p.xpoints[it] / w, p.ypoints[it] / h] }
        } else {
            v = [roi.x1d / w, roi.y1d / h, roi.x2d / w, roi.y2d / h]
        }
        work[shown].pos[m.name] = v.collect { Math.round(it * 10000) / 10000.0d }
        refreshPositions(i)
        setHint("Start position for '${m.name}' taken from the image. Save to keep it.")
    }

    void resetPosition() {
        int i = posList.getSelectedIndex()
        if (i < 0) return
        def m = app.viewByName[shown].meas[i]
        work[shown].pos[m.name] = null
        refreshPositions(i)
    }

    // ---------- save ----------

    void save() {
        commit()

        // validate every view before writing anything
        def parsed = [:]
        for (Map v : app.views) {
            def w = work[v.name]
            Double px = parse(w.px), known = parse(w.known)
            String bad = px == null || px < 0 ? 'Scale bar length must be a number (0 = not set).' :
                         known == null || known <= 0 ? 'Known length must be a number above 0.' :
                         !w.unit ? 'Unit cannot be empty.' : null
            if (bad) {
                viewCombo.setSelectedItem(v.name)
                setHint("View '${v.name}': ${bad}")
                return
            }
            parsed[v.name] = [px: px, known: known]
        }

        // only rewrite keys that changed, so the config keeps its shape
        def updates = [:]
        def rescaled = []
        app.views.each { Map v ->
            def w = work[v.name], p = parsed[v.name]
            String pfx = 'view.' + v.name + '.'
            if (p.px != v.scalePx) updates[pfx + 'scale_distance_px'] = num(p.px)
            if (p.known != v.scaleKnown) updates[pfx + 'scale_known'] = num(p.known)
            if (w.unit != v.unit) updates[pfx + 'scale_unit'] = w.unit
            v.meas.each { Map m ->
                def np = w.pos[m.name]
                if (np != m.pos)
                    updates[pfx + 'position.' + m.name] = np ? np.collect { num(it) }.join(', ') : null
            }
            if (v.scalePx > 0 && (p.px != v.scalePx || p.known != v.scaleKnown)) rescaled << v.name
        }
        if (!updates) {
            frame.dispose()
            return
        }

        try {
            ConfigFile.update(new File(app.cfgPath), updates)
        } catch (IOException e) {
            setHint('Could not write the config file: ' + e.getMessage())
            return
        }

        app.views.each { Map v ->
            def w = work[v.name], p = parsed[v.name]
            v.scalePx = p.px
            v.scaleKnown = p.known
            v.unit = w.unit
            v.meas.each { Map m -> m.pos = w.pos[m.name] }
        }

        rescaled.each { String vn ->
            int n = app.store.rows.count { it.View == vn && it.Type == 'length' }
            if (n == 0) return
            int ans = JOptionPane.showConfirmDialog(frame,
                "${n} length measurement(s) for view '${vn}' were recorded with the old scale.\n" +
                'Recalculate them from their saved lines using the new scale?',
                'Scale changed', JOptionPane.YES_NO_OPTION)
            if (ans == JOptionPane.YES_OPTION) {
                def (int updated, int missing) = app.recalcLengths(vn)
                String msg = "Recalculated ${updated} measurement(s)."
                if (missing) msg += "\n${missing} had no saved line and still use the old scale."
                JOptionPane.showMessageDialog(frame, msg)
            }
        }

        app.settingsSaved()
        frame.dispose()
    }
}

// ------------------------------------------------------------
//  entry point
// ------------------------------------------------------------

final String CONFIG_NAME = 'morphometrics_config.txt'

def dc = new DirectoryChooser('Choose the image folder')
String folder = dc.getDirectory()

if (folder != null) {
    def cfgFile = new File(folder, CONFIG_NAME)
    boolean editorOpen = false

    // Opens the config for editing and waits; false if the user cancels.
    def waitForEdit = { String problem ->
        if (!editorOpen) {
            IJ.open(cfgFile.getAbsolutePath())
            editorOpen = true
        }
        String msg = (problem ? 'Configuration problem:\n' + problem + '\n\n' : '') +
                     'Edit and save\n' + cfgFile.getAbsolutePath() +
                     '\nthen click OK. Cancel to quit.'
        def w = new WaitForUserDialog('Edit configuration', msg)
        w.show()
        return !w.escPressed()
    }

    boolean go = true
    if (!cfgFile.exists()) {
        go = IJ.showMessageWithCancel('No configuration',
            "No ${CONFIG_NAME} found in\n${folder}\n\nCreate one from a template?")
        if (go) {
            writeConfigTemplate(cfgFile)
            go = waitForEdit(null)
        }
    }

    while (go) {
        try {
            new App(parseConfig(cfgFile.getAbsolutePath()), cfgFile.getAbsolutePath(), folder,
                    { String s -> splitCsvLine(s) },
                    { Object v -> csvField(v) },
                    { String s -> parseMeasurements(s) },
                    { Map c, Closure mp -> parseViews(c, mp) })
            break
        } catch (IllegalArgumentException e) {
            go = waitForEdit(e.getMessage())
        }
    }
}
null