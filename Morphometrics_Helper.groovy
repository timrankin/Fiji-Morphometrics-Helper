// ============================================================
//  Zebrafish morphometrics - Fiji / Groovy
//  Script Editor: Language > Groovy, then Run
// ============================================================

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentListener

import ij.IJ
import ij.gui.Line
import ij.gui.PolygonRoi
import ij.gui.Roi
import ij.io.DirectoryChooser
import ij.io.OpenDialog
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
        if (pxv <= 0)
            throw new IllegalArgumentException("config: scale_distance_px missing or invalid for view '${n}'")
        def meas = measParser(ms)
        if (!meas)
            throw new IllegalArgumentException("config: no measurements defined for view '${n}'")

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

// ------------------------------------------------------------
//  application
// ------------------------------------------------------------

class App {

    String folder, folderName
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

    App(Map cfg, String folder, Closure splitter, Closure quoter, Closure measParser,
        Closure viewParser) {
        this.folder = folder
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
        frame.add(main)
        frame.pack()
        frame.setLocation(20, 80)
        frame.setVisible(true)
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

        rois = [:]
        String path = roiPath(i)
        if (new File(path).exists()) {
            rm.runCommand('reset')
            rm.runCommand('Open', path)
            rm.getRoisAsArray().each { Roi roi ->
                if (roi.getName()) rois[roi.getName()] = roi
            }
            rm.runCommand('reset')
        }

        refreshMeasList()
        if (measModel.getSize() > 0) measList.setSelectedIndex(0)
    }

    void applyCalibration() {
        if (imp == null || viewSpec == null) return
        def cal = imp.getCalibration()
        cal.pixelWidth = viewSpec.scaleKnown / viewSpec.scalePx
        cal.pixelHeight = cal.pixelWidth
        cal.setUnit(viewSpec.unit)
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

        if (rois.containsKey(spec.name)) {
            imp.setRoi(rois[spec.name])
        } else {
            double w = imp.getWidth(), h = imp.getHeight()
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
            if (roi.getType() != Roi.LINE) {
                IJ.showMessage("'${spec.name}' needs a straight line selection.")
                return
            }
            value = lengthOf(roi)
        }

        roi.setName(spec.name)
        rois[spec.name] = roi

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

    void finish() {
        store.save()
        saveAssignments()
        if (imp != null) {
            imp.changes = false
            imp.close()
        }
        rm.close()
        frame.dispose()
        IJ.log('Session ended. Results: ' + store.path)
    }
}

// ------------------------------------------------------------
//  entry point
// ------------------------------------------------------------

def od = new OpenDialog('Select config file', null)
if (od.getFileName() != null) {
    String cfgPath = new File(od.getDirectory(), od.getFileName()).getAbsolutePath()
    def cfg = parseConfig(cfgPath)

    def dc = new DirectoryChooser('Choose the image folder')
    String folder = dc.getDirectory()

    if (folder != null) {
        try {
            new App(cfg, folder,
                    { String s -> splitCsvLine(s) },
                    { Object v -> csvField(v) },
                    { String s -> parseMeasurements(s) },
                    { Map c, Closure mp -> parseViews(c, mp) })
        } catch (IllegalArgumentException e) {
            IJ.showMessage('Configuration problem', e.getMessage())
        }
    }
}
null