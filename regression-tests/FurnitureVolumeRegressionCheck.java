/*
 * Standalone Swing integration check for Task 3; leaves assignment-tests unchanged.
 * Run from the repository root with Java 8 and a graphical desktop:
 * javac -cp 'target/classes:SweetHome3D/lib/*' -d /tmp/sweethome-volume-check regression-tests/FurnitureVolumeRegressionCheck.java
 * java -cp '/tmp/sweethome-volume-check:target/classes:SweetHome3D/lib/*' FurnitureVolumeRegressionCheck
 * Add --show to leave the real HomePane and its Volume sort menu open for inspection.
 */
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import javax.swing.Action;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import com.eteks.sweethome3d.io.FileUserPreferences;
import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomePieceOfFurniture.SortableProperty;
import com.eteks.sweethome3d.model.LengthUnit;
import com.eteks.sweethome3d.model.PieceOfFurniture;
import com.eteks.sweethome3d.swing.FurnitureTable;
import com.eteks.sweethome3d.swing.HomePane;
import com.eteks.sweethome3d.swing.SwingViewFactory;
import com.eteks.sweethome3d.viewcontroller.FurnitureController;
import com.eteks.sweethome3d.viewcontroller.HomeController;
import com.eteks.sweethome3d.viewcontroller.HomeView;

public class FurnitureVolumeRegressionCheck {
  private static int checks;

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
    System.out.println("PASS: " + message);
  }

  private static HomePieceOfFurniture piece(String name, float width, float depth, float height) {
    return new HomePieceOfFurniture(new CatalogPieceOfFurniture(name, null, null, width, depth, height, true, false));
  }

  private static int column(FurnitureTable table, SortableProperty property) {
    for (int i = 0; i < table.getColumnCount(); i++) {
      if (table.getColumnModel().getColumn(i).getIdentifier() == property) return i;
    }
    return -1;
  }

  private static JMenuItem menu(Component root, String name) {
    if (root instanceof JMenuItem && name.equals(((JMenuItem)root).getText())) return (JMenuItem)root;
    Component[] children = root instanceof JMenu ? ((JMenu)root).getMenuComponents()
        : root instanceof Container ? ((Container)root).getComponents() : new Component[0];
    for (Component child : children) {
      JMenuItem found = menu(child, name);
      if (found != null) return found;
    }
    return null;
  }

  private static String rendered(FurnitureTable table, int row, SortableProperty property) {
    int column = column(table, property);
    Component renderer = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
    return ((JLabel)renderer).getText();
  }

  private static void ordered(FurnitureTable table, boolean descending, String message) {
    for (int row = 1; row < table.getRowCount(); row++) {
      float previous = ((HomePieceOfFurniture)table.getValueAt(row - 1, 0)).getVolume();
      float current = ((HomePieceOfFurniture)table.getValueAt(row, 0)).getVolume();
      check(descending ? previous >= current : previous <= current, message + " row " + row);
    }
  }

  private static Home roundTrip(Home home, Path directory, String name) throws Exception {
    String file = directory.resolve(name + ".sh3d").toString();
    HomeFileRecorder recorder = new HomeFileRecorder();
    recorder.writeHome(home, file);
    return recorder.readHome(file);
  }

  private static void arrangeForInspection(Component component) {
    if (component instanceof JSplitPane) {
      JSplitPane split = (JSplitPane)component;
      split.setDividerLocation(split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? 720 : 300);
    }
    if (component instanceof Container) {
      for (Component child : ((Container)component).getComponents()) arrangeForInspection(child);
    }
  }

  private static void run(boolean show) throws Exception {
    Path directory = Files.createTempDirectory("sweethome-volume-regression-");
    FileUserPreferences preferences = new FileUserPreferences(directory.resolve("preferences").toFile(), new File[0]);
    preferences.setLanguage("en");
    preferences.setUnit(LengthUnit.CENTIMETER);
    PieceOfFurniture catalog = new CatalogPieceOfFurniture("Catalog", null, null, 2, 3, 4, true, false);
    check(catalog.getVolume() == 24, "Catalog furniture exposes computed volume through PieceOfFurniture");
    check(new HomePieceOfFurniture(catalog).getVolume() == 24, "Home furniture computes the same volume");
    check(piece("Fractional", .5f, 2, 3).getVolume() == 3, "Fractional dimensions");
    check(piece("Zero", 0, 2, 3).getVolume() == 0, "Zero dimension");
    Home home = new Home();
    HomePieceOfFurniture medium = piece("Cabinet", 2, 3, 4);
    HomePieceOfFurniture large = piece("Table", 3, 4, 10);
    HomePieceOfFurniture small = piece("Small box", 1, 2, 3);
    home.addPieceOfFurniture(medium, 0);
    home.addPieceOfFurniture(large, 1);
    home.addPieceOfFurniture(small, 2);
    check(home.getFurnitureVisibleProperties().equals(Arrays.asList(SortableProperty.NAME,
        SortableProperty.WIDTH, SortableProperty.DEPTH, SortableProperty.HEIGHT,
        SortableProperty.VOLUME, SortableProperty.VISIBLE)), "New-home columns preserve existing order and include Volume after Height");
    Comparator<HomePieceOfFurniture> comparator = HomePieceOfFurniture.getFurnitureComparator(SortableProperty.VOLUME);
    check(comparator.compare(small, large) < 0 && comparator.compare(large, small) > 0, "Numeric comparator compares both directions");
    check(comparator.compare(medium, piece("Equal", 1, 6, 4)) == 0, "Equal volumes compare equally");
    HomeController controller = new HomeController(home, preferences, new SwingViewFactory());
    HomePane pane = (HomePane)controller.getView();
    FurnitureController furniture = controller.getFurnitureController();
    FurnitureTable table = (FurnitureTable)furniture.getView();
    check(column(table, SortableProperty.VOLUME) >= 0, "Volume is visible in the actual table");
    check("24".equals(rendered(table, 0, SortableProperty.VOLUME)), "Volume renderer uses current length formatter");
    JMenu furnitureMenu = (JMenu)menu(pane.getJMenuBar(), "Furniture");
    JMenu sortMenu = (JMenu)menu(furnitureMenu, "Sort by");
    JMenu displayMenu = (JMenu)menu(furnitureMenu, "Display column");
    JMenuItem sortVolume = menu(sortMenu, "Volume");
    JMenuItem displayVolume = menu(displayMenu, "Volume");
    check(sortVolume != null && sortVolume.isEnabled(), "Enabled Furniture > Sort by > Volume");
    check(displayVolume != null && displayVolume.isEnabled(), "Enabled Furniture > Display column > Volume");
    JPopupMenu context = table.getComponentPopupMenu();
    JMenuItem contextSort = menu(menu(context, "Sort by"), "Volume");
    JMenuItem contextDisplay = menu(menu(context, "Display column"), "Volume");
    check(contextSort != null && contextSort.isEnabled(), "Enabled context-menu Volume sort control");
    check(contextDisplay != null && contextDisplay.isEnabled(), "Enabled context-menu Volume display control");
    check("Sort furniture by volume".equals(pane.getActionMap().get(HomeView.ActionType.SORT_HOME_FURNITURE_BY_VOLUME).getValue(Action.SHORT_DESCRIPTION)), "English sorting tooltip");
    check("Display volume column".equals(pane.getActionMap().get(HomeView.ActionType.DISPLAY_HOME_FURNITURE_VOLUME).getValue(Action.SHORT_DESCRIPTION)), "English display tooltip");
    sortVolume.doClick();
    check(home.getFurnitureSortedProperty() == SortableProperty.VOLUME, "Menu invokes controller Volume sorting");
    ordered(table, false, "Ascending numeric volume order (6, 24, 120)");
    JMenuItem descending = menu(sortMenu, "Descending order");
    descending.doClick(); ordered(table, true, "Descending order menu");
    descending.doClick();
    contextSort.doClick();
    check(home.getFurnitureSortedProperty() == null, "Context sort toggles the current criterion off");
    contextSort.doClick(); ordered(table, false, "Context sort restores ascending order");
    int[] events = {0};
    table.getModel().addTableModelListener(event -> events[0]++);
    medium.setWidth(50);
    check(medium.getVolume() == 600 && table.getValueAt(2, 0) == medium, "Width change recomputes volume and moves sorted row");
    medium.setDepth(.1f);
    check(medium.getVolume() == 20 && table.getValueAt(1, 0) == medium, "Depth change recomputes volume and moves sorted row");
    medium.setHeight(.1f);
    check(medium.getVolume() == .5f && table.getValueAt(0, 0) == medium, "Height change recomputes volume and moves sorted row");
    check(events[0] >= 3, "Existing dimension events notify the actual table model");
    check(rendered(table, 0, SortableProperty.VOLUME).equals(preferences.getLengthUnit().getFormat().format(medium.getVolume())), "Rendered volume updates after resizing");
    preferences.setUnit(LengthUnit.METER);
    check(rendered(table, 0, SortableProperty.VOLUME).equals(preferences.getLengthUnit().getFormat().format(medium.getVolume())), "Changing units reuses the assignment's length formatter");
    preferences.setUnit(LengthUnit.CENTIMETER);
    displayVolume.doClick();
    check(column(table, SortableProperty.VOLUME) == -1 && !contextDisplay.isSelected(), "Menu hides Volume and context checkbox follows");
    Home hidden = roundTrip(home, directory, "hidden");
    check(!hidden.getFurnitureVisibleProperties().contains(SortableProperty.VOLUME), "Hidden Volume persists through actual home save/reload");
    check(hidden.getFurnitureSortedProperty() == SortableProperty.VOLUME, "Volume sort criterion persists through home save/reload");
    contextDisplay.doClick();
    check(column(table, SortableProperty.VOLUME) == 4 && displayVolume.isSelected(), "Context action restores Volume after Height and synchronizes menu checkbox");
    medium.setWidth(2); medium.setDepth(3); medium.setHeight(4);
    furniture.sortFurniture(SortableProperty.WIDTH);
    check(((HomePieceOfFurniture)table.getValueAt(0, 0)).getWidth() == 1, "Existing Width sorting still works");
    check("2".equals(rendered(table, 1, SortableProperty.WIDTH)), "Existing Width renderer still works");
    furniture.sortFurniture(SortableProperty.VOLUME);
    furniture.sortFurniture(SortableProperty.VOLUME);
    ordered(table, true, "Header sorting controller cycles to descending Volume");
    Home saved = roundTrip(home, directory, "visible");
    check(saved.getFurnitureVisibleProperties().contains(SortableProperty.VOLUME) && saved.isFurnitureDescendingSorted(), "Visible Volume and descending order persist through save/reload");
    check(saved.getFurniture().get(0).getVolume() == 24, "Reloaded furniture computes volume from saved dimensions");
    Home sample = new HomeFileRecorder().readHome("samples/MultiLevelHome.sh3d");
    // This sample's Home.xml already lists VOLUME, although the starter ignored it.
    check(sample.getFurnitureVisibleProperties().equals(Arrays.asList(SortableProperty.NAME,
        SortableProperty.WIDTH, SortableProperty.DEPTH, SortableProperty.HEIGHT,
        SortableProperty.VOLUME, SortableProperty.VISIBLE)), "Existing sample restores the columns saved in its Home.xml");
    check(sample.getFurnitureSortedProperty() == SortableProperty.HEIGHT && sample.isFurnitureDescendingSorted(),
        "Existing sample retains its saved Height sorting and direction");
    StringWriter csv = new StringWriter(); table.exportToCSV(csv, '\t');
    check(csv.toString().contains("Volume") && csv.toString().contains("120"), "CSV includes the visible Volume header and formatted values");
    // Exercise the actual header click handler, resetting to ascending first.
    home.setFurnitureDescendingSorted(false);
    int index = column(table, SortableProperty.VOLUME);
    java.awt.Rectangle header = table.getTableHeader().getHeaderRect(index);
    table.getTableHeader().dispatchEvent(new MouseEvent(table.getTableHeader(), MouseEvent.MOUSE_CLICKED,
        System.currentTimeMillis(), 0, header.x + header.width / 2, header.height / 2, 1, false, MouseEvent.BUTTON1));
    check(home.isFurnitureDescendingSorted(), "Actual Volume header click changes ascending to descending");
    home.setFurnitureDescendingSorted(false);
    ordered(table, false, "Final ascending view");
    System.out.println("ALL " + checks + " REGRESSION CHECKS PASSED");
    if (show) {
      JFrame frame = new JFrame("Sweet Home 3D - Task 3 Volume verification");
      frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
      frame.setContentPane(pane);
      frame.setSize(1200, 840);
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
      SwingUtilities.invokeLater(() -> {
        arrangeForInspection(pane);
        MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {
            pane.getJMenuBar(), furnitureMenu, furnitureMenu.getPopupMenu(), sortMenu, sortMenu.getPopupMenu(), sortVolume});
        System.out.println("READY_FOR_SCREENSHOT: populated table and real Furniture > Sort by menu");
      });
    }
  }

  public static void main(String[] args) throws Exception {
    Locale.setDefault(Locale.US);
    System.setProperty("com.eteks.sweethome3d.no3D", "true");
    boolean show = Arrays.asList(args).contains("--show");
    SwingUtilities.invokeAndWait(() -> {
      try { run(show); }
      catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    });
    if (!show) System.exit(0);
  }
}
