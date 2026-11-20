/*
 * Standalone Task 4 integration checks using production Swing printing and PDF export.
 * Run from the repository root with Java 8 and a graphical desktop:
 * javac -cp 'target/classes:SweetHome3D/lib/*' -d /tmp/sweethome-print-check regression-tests/AllLevelsPrintingRegressionCheck.java
 * java -cp '/tmp/sweethome-print-check:target/classes:SweetHome3D/lib/*' AllLevelsPrintingRegressionCheck /tmp/task4-all-levels.pdf
 * The optional output path receives the actual HomePDFPrinter output for the sample.
 * Preferences and other test documents are isolated in temporary directories.
 */
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumnModel;
import com.eteks.sweethome3d.io.FileUserPreferences;
import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomePieceOfFurniture.SortableProperty;
import com.eteks.sweethome3d.model.HomePrint;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.swing.FileContentManager;
import com.eteks.sweethome3d.swing.FurnitureTable;
import com.eteks.sweethome3d.swing.HomePDFPrinter;
import com.eteks.sweethome3d.swing.HomePrintableComponent;
import com.eteks.sweethome3d.swing.PlanComponent;
import com.eteks.sweethome3d.swing.SwingViewFactory;
import com.eteks.sweethome3d.viewcontroller.FurnitureController;
import com.eteks.sweethome3d.viewcontroller.HomeController;
import com.eteks.sweethome3d.viewcontroller.PlanController;
import com.eteks.sweethome3d.viewcontroller.PlanView;
import com.eteks.sweethome3d.viewcontroller.View;
import com.lowagie.text.pdf.PdfReader;

public class AllLevelsPrintingRegressionCheck {
  private static int checks;
  private static Path temporary;
  private static final Font FONT = new Font("SansSerif", Font.PLAIN, 12);

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
    System.out.println("PASS: " + message);
  }

  private static Home home(int levelCount, int pieces) {
    Home home = new Home();
    for (int i = 0; i < levelCount; i++) {
      Level level = new Level("Level " + i, i * 250, 12, 240);
      home.addLevel(level);
      Wall wall = new Wall(0, 0, 200 + i * 100, 0, 10, 240);
      wall.setLevel(level); home.addWall(wall);
    }
    for (int i = 0; i < pieces; i++) {
      HomePieceOfFurniture piece = new HomePieceOfFurniture(new CatalogPieceOfFurniture("Piece " + i, null, null, 20 + i, 30, 40, true, false));
      home.addPieceOfFurniture(piece, i);
      piece.setLevel(levelCount == 0 ? null : home.getLevels().get(i % levelCount));
    }
    home.setSelectedLevel(levelCount == 0 ? null : home.getLevels().get(levelCount - 1));
    return home;
  }

  private static PageFormat pageFormat() {
    Paper paper = new Paper(); paper.setSize(612, 792); paper.setImageableArea(36, 36, 540, 720);
    PageFormat format = new PageFormat(); format.setPaper(paper);
    return java.awt.print.PrinterJob.getPrinterJob().validatePage(format);
  }

  private static int print(Printable printable, int page) throws PrinterException {
    Graphics2D graphics = new BufferedImage(612, 792, BufferedImage.TYPE_INT_RGB).createGraphics();
    try { return printable.print(graphics, pageFormat(), page); }
    finally { graphics.dispose(); }
  }

  private static class Factory extends SwingViewFactory {
    RecordingTable table;
    RecordingPlan plan;
    final List<String> successfulPages = new ArrayList<String>();
    @Override public View createFurnitureView(Home home, UserPreferences preferences, FurnitureController controller) {
      return table = new RecordingTable(home, preferences, controller, successfulPages);
    }
    @Override public PlanView createPlanView(Home home, UserPreferences preferences, PlanController controller) {
      return plan = new RecordingPlan(home, preferences, controller, successfulPages);
    }
    @Override public View createView3D(Home home, UserPreferences preferences, com.eteks.sweethome3d.viewcontroller.HomeController3D controller) {
      return null;
    }
  }

  private static class RecordingTable extends FurnitureTable {
    final List<String> successfulPages;
    List<HomePieceOfFurniture> printedRows;
    List<Object> printedColumns;
    int failure;
    CountDownLatch renderingStarted, releaseRendering;
    RecordingTable(Home home, UserPreferences preferences, FurnitureController controller, List<String> pages) {
      super(home, preferences, controller); successfulPages = pages;
    }
    @Override public Printable getPrintable(PrintMode mode, MessageFormat header, MessageFormat footer) {
      final Printable delegate = super.getPrintable(mode, header, footer);
      return (graphics, format, page) -> {
        printedRows = new ArrayList<HomePieceOfFurniture>();
        printedColumns = new ArrayList<Object>();
        for (int row = 0; row < getRowCount(); row++) printedRows.add((HomePieceOfFurniture)getValueAt(row, 0));
        for (int column = 0; column < getColumnCount(); column++) printedColumns.add(getColumnModel().getColumn(column).getIdentifier());
        pauseRendering(renderingStarted, releaseRendering);
        if (failure == 1) throw new PrinterException("Injected table printer failure");
        if (failure == 2) throw new IllegalStateException("Injected table renderer failure");
        if (failure == 3) throw new AssertionError("Injected table rendering error");
        int result = delegate.print(graphics, format, page);
        if (result == PAGE_EXISTS) successfulPages.add("table:" + page);
        return result;
      };
    }
  }

  private static class RecordingPlan extends PlanComponent {
    final Home home;
    final List<String> successfulPages;
    Level failAtLevel;
    CountDownLatch renderingStarted, releaseRendering;
    boolean printing;
    boolean recorded;
    boolean renderedOnDispatchThread = true;
    Level renderedLevel;
    RecordingPlan(Home home, UserPreferences preferences, PlanController controller, List<String> pages) {
      super(home, preferences, controller); this.home = home; successfulPages = pages;
    }
    @Override public int print(Graphics graphics, PageFormat format, int page) {
      printing = true; recorded = false;
      try { return super.print(graphics, format, page); }
      finally { printing = false; }
    }
    @Override protected List<Selectable> getPaintedItems() {
      if (printing) {
        pauseRendering(renderingStarted, releaseRendering);
        renderedOnDispatchThread &= SwingUtilities.isEventDispatchThread();
        renderedLevel = home.getSelectedLevel();
        if (failAtLevel != null && renderedLevel == failAtLevel) throw new IllegalStateException("Injected plan rendering failure");
        if (!recorded) { successfulPages.add("plan:" + (renderedLevel == null ? "none" : renderedLevel.getName())); recorded = true; }
      }
      return super.getPaintedItems();
    }
  }

  private static class Fixture implements AutoCloseable {
    final Home home;
    final FileUserPreferences preferences;
    final Factory factory = new Factory();
    final HomeController controller;
    final JFrame frame;
    HomePrintableComponent printable;
    Fixture(Home home) throws Exception {
      this(home, false);
    }
    Fixture(Home home, boolean usePrinterDefaults) throws Exception {
      this.home = home;
      if (home.getPrint() == null && !usePrinterDefaults) {
        // Keep synthetic fixtures deterministic and consistent with pageFormat().
        home.setPrint(new HomePrint(HomePrint.PaperOrientation.PORTRAIT, 612, 792,
            36, 36, 36, 36, true, true, false, null, null, null));
      }
      preferences = new FileUserPreferences(Files.createTempDirectory(temporary, "prefs-").toFile(), new File[0]);
      preferences.setLanguage("en");
      controller = new HomeController(home, preferences, factory, new FileContentManager(preferences));
      frame = new JFrame("All-level printing regression check");
      frame.setContentPane((java.awt.Container)controller.getView());
      frame.setSize(1000, 720); frame.setLocationRelativeTo(null); frame.setVisible(true);
      printable = new HomePrintableComponent(home, controller, FONT);
    }
    @Override public void close() { frame.dispose(); }
  }

  private static class Snapshot {
    final Fixture fixture;
    final Level selected;
    final List<Selectable> items;
    final List<SortableProperty> columns;
    final List<Boolean> visible = new ArrayList<Boolean>();
    final boolean modified, allLevels;
    final SortableProperty sorted;
    final boolean descending;
    final Camera top, observer;
    final TableColumnModel columnModel;
    final Color grid;
    final FurnitureTable.FurnitureFilter filter;
    final HomePrint print;
    Snapshot(Fixture fixture) {
      this.fixture = fixture; Home home = fixture.home;
      selected = home.getSelectedLevel(); items = new ArrayList<Selectable>(home.getSelectedItems());
      columns = new ArrayList<SortableProperty>(home.getFurnitureVisibleProperties());
      for (Level level : home.getLevels()) visible.add(level.isVisible());
      modified = home.isModified(); allLevels = home.isAllLevelsSelection();
      sorted = home.getFurnitureSortedProperty(); descending = home.isFurnitureDescendingSorted();
      top = home.getTopCamera().clone(); observer = home.getObserverCamera().clone();
      columnModel = fixture.factory.table.getColumnModel(); grid = fixture.factory.table.getGridColor();
      filter = fixture.factory.table.getFurnitureFilter(); print = home.getPrint();
    }
    void restored(String context) {
      Home home = fixture.home;
      check(home.getSelectedLevel() == selected && home.getSelectedItems().equals(items)
          && home.isAllLevelsSelection() == allLevels, context + ": selected level and selection restored");
      List<Boolean> current = new ArrayList<Boolean>();
      for (Level level : home.getLevels()) current.add(level.isVisible());
      check(visible.equals(current) && cameraEquals(top, home.getTopCamera()) && cameraEquals(observer, home.getObserverCamera()), context + ": level visibility and cameras restored");
      check(home.isModified() == modified && home.getPrint() == print
          && home.getFurnitureSortedProperty() == sorted && home.isFurnitureDescendingSorted() == descending,
          context + ": modified flag, print configuration and furniture sorting unchanged");
      check(home.getFurnitureVisibleProperties().equals(columns)
          && fixture.factory.table.getColumnModel() == columnModel
          && fixture.factory.table.getGridColor().equals(grid)
          && fixture.factory.table.getFurnitureFilter() == filter, context + ": table columns, model, grid and filter restored");
    }
  }

  private static void pauseRendering(CountDownLatch started, CountDownLatch release) {
    if (started != null) {
      started.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Rendering test timed out");
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt(); throw new AssertionError(ex);
      }
    }
  }

  private static void backgroundInterruption(boolean table) throws Exception {
    Fixture [] fixture = new Fixture [1]; Snapshot [] before = new Snapshot [1];
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    AtomicBoolean interrupted = new AtomicBoolean();
    SwingUtilities.invokeAndWait(() -> {
      try {
        fixture [0] = new Fixture(home(3, 3)); seedState(fixture [0]); before [0] = new Snapshot(fixture [0]);
        if (table) { fixture [0].factory.table.renderingStarted = started; fixture [0].factory.table.releaseRendering = release; }
        else { fixture [0].factory.plan.renderingStarted = started; fixture [0].factory.plan.releaseRendering = release; }
      } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    });
    Thread worker = new Thread(() -> {
      try { print(table ? fixture [0].factory.table : fixture [0].factory.plan, 0); }
      catch (Throwable error) { failure.set(error); }
      finally { interrupted.set(Thread.currentThread().isInterrupted()); returned.countDown(); }
    }, "printing-cancellation-regression");
    worker.start();
    try {
      check(started.await(10, TimeUnit.SECONDS), "Background " + (table ? "table" : "plan") + " rendering begins");
      worker.interrupt();
      check(!returned.await(100, TimeUnit.MILLISECONDS), "Interrupted caller waits until Swing restores temporary state");
    } finally { release.countDown(); }
    check(returned.await(10, TimeUnit.SECONDS), "Cancelled background printing finishes after restoration");
    check(failure.get() != null && interrupted.get(), "Cancellation propagates and preserves the thread interrupt flag");
    SwingUtilities.invokeAndWait(() -> {
      try { before [0].restored("Background cancellation"); fixture [0].close(); }
      catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    });
  }

  private static boolean cameraEquals(Camera a, Camera b) {
    return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ()
        && a.getYaw() == b.getYaw() && a.getPitch() == b.getPitch() && a.getFieldOfView() == b.getFieldOfView();
  }

  private static void seedState(Fixture fixture) {
    Home home = fixture.home;
    if (!home.getFurniture().isEmpty()) home.setSelectedItems(Arrays.asList(home.getFurniture().get(home.getFurniture().size() - 1)));
    home.setAllLevelsSelection(false);
    for (int i = 0; i < home.getLevels().size(); i++) home.getLevels().get(i).setVisible(i % 2 == 0);
    // Exercise camera clamping on temporary level changes; restoration must recover the exact original value.
    home.getObserverCamera().setZ(1);
    home.setModified(false);
  }

  private static void paginationAndFiltering() throws Exception {
    Home home = home(4, 12);
    home.getLevels().get(1).setViewable(false);
    HomePieceOfFurniture unassigned = new HomePieceOfFurniture(new CatalogPieceOfFurniture("Unassigned", null, null, 10, 20, 30, true, false));
    home.addPieceOfFurniture(unassigned, home.getFurniture().size()); unassigned.setLevel(null);
    try (Fixture fixture = new Fixture(home)) {
      fixture.controller.getFurnitureController().sortFurniture(SortableProperty.VOLUME);
      fixture.controller.getFurnitureController().toggleFurnitureSortOrder();
      seedState(fixture); Snapshot original = new Snapshot(fixture);
      int count = fixture.printable.getPageCount();
      check(count == 4, "Three viewable levels plus one furniture page");
      check(fixture.factory.successfulPages.equals(Arrays.asList("table:0", "plan:Level 0", "plan:Level 2", "plan:Level 3")), "Furniture pages precede plans in home order; non-viewable level skipped");
      List<HomePieceOfFurniture> rows = fixture.factory.table.printedRows;
      check(rows.size() == 10 && new HashSet<HomePieceOfFurniture>(rows).size() == 10, "All eligible furniture, including unassigned furniture, appears once");
      check(!rows.contains(home.getFurniture().get(1)), "Furniture on non-viewable level excluded");
      check(rows.contains(home.getFurniture().get(2)), "Viewable furniture included even when its level's 3D visibility is false");
      check(fixture.factory.table.printedColumns.contains(SortableProperty.LEVEL) && fixture.factory.table.printedColumns.contains(SortableProperty.VOLUME), "Printed table has Level and existing Volume columns");
      original.restored("Page counting");
      int priorEvents = fixture.factory.successfulPages.size();
      check(fixture.printable.getPageCount() == count && fixture.factory.successfulPages.size() == priorEvents, "Repeated page counting uses a stable cache");
      String[] expected = {"table:0", "plan:Level 0", "plan:Level 2", "plan:Level 3"};
      for (int repeat = 0; repeat < 2; repeat++) {
        for (int page = count - 1; page >= 0; page--) {
          fixture.factory.successfulPages.clear();
          check(print(fixture.printable, page) == Printable.PAGE_EXISTS, "Repeated reverse-order request exists: " + page);
          check(fixture.factory.successfulPages.equals(Arrays.asList(expected[page])), "Reverse-order page maps to correct section/level: " + page + " " + fixture.factory.successfulPages);
          original.restored("Repeated page " + page);
        }
      }
      check(print(fixture.printable, -1) == Printable.NO_SUCH_PAGE && print(fixture.printable, count) == Printable.NO_SUCH_PAGE, "Negative and out-of-range pages do not exist");
      original.restored("Missing page");
      fixture.printable = new HomePrintableComponent(home, fixture.controller, FONT);
      fixture.factory.successfulPages.clear();
      check(print(fixture.printable, 3) == Printable.PAGE_EXISTS, "Fresh coordinator handles a last-page-first request");
      check("plan:Level 3".equals(fixture.factory.successfulPages.get(fixture.factory.successfulPages.size() - 1)), "Fresh coordinator establishes furniture offsets before random access");
      original.restored("Fresh random access");
    }
  }

  private static void tableStateAndFailures() throws Exception {
    for (boolean initiallyVisible : new boolean[] {false, true}) {
      try (Fixture fixture = new Fixture(home(3, 6))) {
        if (initiallyVisible) {
          fixture.controller.getFurnitureController().toggleFurnitureVisibleProperty(SortableProperty.LEVEL);
          TableColumnModel columns = fixture.factory.table.getColumnModel();
          columns.moveColumn(columns.getColumnIndex(SortableProperty.LEVEL), 0);
        }
        HomePieceOfFurniture excluded = fixture.home.getFurniture().get(0);
        fixture.factory.table.setFurnitureFilter((h, p) -> p != excluded);
        fixture.factory.table.setGridColor(Color.BLUE);
        seedState(fixture); Snapshot original = new Snapshot(fixture);
        check(print(fixture.factory.table, 0) == Printable.PAGE_EXISTS, "Real JTable prints with initially visible Level=" + initiallyVisible);
        check(fixture.factory.table.printedRows.size() == 5 && !fixture.factory.table.printedRows.contains(excluded), "Existing custom furniture filter honored");
        check(java.util.Collections.frequency(fixture.factory.table.printedColumns, SortableProperty.LEVEL) == 1, "Printed Level column is present exactly once");
        if (initiallyVisible) check(fixture.factory.table.printedColumns.get(0) == SortableProperty.LEVEL, "Visible Level column retains its user-selected position");
        original.restored("Successful table printing");
        for (int failure = 1; failure <= 3; failure++) {
          fixture.factory.table.failure = failure;
          boolean failed = false;
          try { print(fixture.factory.table, 0); }
          catch (PrinterException | IllegalStateException | AssertionError injected) { failed = true; }
          check(failed, "Injected table failure propagates: " + failure);
          original.restored("Table failure " + failure);
        }
        fixture.factory.table.failure = 1;
        boolean failed = false;
        try { fixture.printable.getPageCount(); } catch (RuntimeException injected) { failed = true; }
        check(failed, "Page-count failure propagates"); original.restored("Failed page count");
        fixture.factory.table.failure = 0;
        check(fixture.printable.getPageCount() == 4, "Page-count cache recovers after failure without accumulating pages");
        original.restored("Page-count retry");
        fixture.factory.table.failure = 2;
        Graphics2D graphics = new BufferedImage(612, 792, BufferedImage.TYPE_INT_RGB).createGraphics();
        graphics.translate(5, 7); graphics.setClip(1, 2, 300, 400);
        AffineTransform transform = graphics.getTransform(); java.awt.Rectangle clip = graphics.getClipBounds();
        PageFormat format = pageFormat();
        double x = format.getImageableX(), y = format.getImageableY(), width = format.getImageableWidth(), height = format.getImageableHeight();
        try { fixture.printable.print(graphics, format, 0); } catch (IllegalStateException expected) { }
        check(graphics.getTransform().equals(transform) && graphics.getClipBounds().equals(clip), "Failed coordinated rendering restores caller graphics state");
        check(format.getImageableX() == x && format.getImageableY() == y && format.getImageableWidth() == width && format.getImageableHeight() == height, "Failed coordinated rendering restores PageFormat paper");
        graphics.dispose(); original.restored("Coordinated rendering failure");
      }
    }
  }

  private static void plansAndLevelCounts() throws Exception {
    for (int levelCount : new int[] {1, 2, 5}) {
      Home home = home(levelCount, levelCount);
      home.setPrint(new HomePrint(HomePrint.PaperOrientation.PORTRAIT, 612, 792, 36, 36, 36, 36, false, true, false, 10f, "$level", "$pageNumber/$pageCount"));
      try (Fixture fixture = new Fixture(home)) {
        seedState(fixture); Snapshot original = new Snapshot(fixture);
        check(fixture.printable.getPageCount() == levelCount, "One fitted plan per viewable level even with a large explicit scale: " + levelCount);
        original.restored("Plan-only counting");
        for (int page = 0; page < levelCount; page++) {
          check(print(fixture.factory.plan, page) == Printable.PAGE_EXISTS && fixture.factory.plan.renderedLevel == home.getLevels().get(page), "Direct PlanComponent page selects correct level: " + page);
          original.restored("Direct plan page");
        }
        fixture.factory.plan.failAtLevel = home.getLevels().get(0);
        boolean failed = false;
        try { print(fixture.factory.plan, 0); } catch (IllegalStateException injected) { failed = true; }
        check(failed, "Plan rendering failure propagates"); original.restored("Plan rendering failure");
        fixture.factory.plan.failAtLevel = null;
        check(print(fixture.factory.plan, 0) == Printable.PAGE_EXISTS, "Plan printing succeeds after a rendering failure");
        check(fixture.factory.plan.renderedOnDispatchThread, "Plan selection and rendering execute on Swing dispatch thread");
      }
    }
    Home empty = home(2, 0); empty.getCompass().setVisible(false);
    for (Wall wall : new ArrayList<Wall>(empty.getWalls())) empty.deleteWall(wall);
    empty.setPrint(new HomePrint(HomePrint.PaperOrientation.PORTRAIT, 612, 792, 36, 36, 36, 36, false, true, false, null, null, null));
    try (Fixture fixture = new Fixture(empty)) {
      check(fixture.printable.getPageCount() == 2, "Empty viewable levels still have one plan page each");
      for (Level level : empty.getLevels()) level.setViewable(false);
      HomePrintableComponent hidden = new HomePrintableComponent(empty, fixture.controller, FONT);
      check(hidden.getPageCount() == 0, "No plan pages when every named level is non-viewable");
    }
    try (Fixture fixture = new Fixture(home(0, 1))) {
      Snapshot original = new Snapshot(fixture);
      check(print(fixture.factory.plan, 0) == Printable.PAGE_EXISTS && print(fixture.factory.plan, 1) == Printable.NO_SUCH_PAGE, "Home without named levels retains its ordinary single-plan printing");
      original.restored("No-level plan");
    }
    try (Fixture fixture = new Fixture(home(3, 180))) {
      fixture.home.getLevels().get(1).setViewable(false);
      Snapshot original = new Snapshot(fixture);
      int total = fixture.printable.getPageCount();
      int furniturePages = 0;
      for (String page : fixture.factory.successfulPages) if (page.startsWith("table:")) furniturePages++;
      check(furniturePages > 1 && total == furniturePages + 2, "All furniture pagination precedes two viewable plan pages");
      check(fixture.factory.successfulPages.get(furniturePages).equals("plan:Level 0")
          && fixture.factory.successfulPages.get(furniturePages + 1).equals("plan:Level 2"), "Multi-page table offsets preserve plan order");
      check(fixture.factory.table.printedRows.size() == 120 && new HashSet<HomePieceOfFurniture>(fixture.factory.table.printedRows).size() == 120, "Large printed table contains every eligible row once");
      original.restored("Multi-page furniture table");
    }
  }

  private static void selectedLevelsAndCoordinatedFailures() throws Exception {
    Home home = home(3, 6);
    home.getLevels().get(1).setViewable(false);
    try (Fixture fixture = new Fixture(home)) {
      List<Level> startingLevels = new ArrayList<Level>(home.getLevels()); startingLevels.add(null);
      for (Level starting : startingLevels) {
        home.setSelectedLevel(starting); seedState(fixture); Snapshot original = new Snapshot(fixture);
        HomePrintableComponent operation = new HomePrintableComponent(home, fixture.controller, FONT);
        check(operation.getPageCount() == 3, "All viewable levels print regardless of initial selected level: " + (starting == null ? "null" : starting.getName()));
        for (int page = 0; page < 3; page++) check(print(operation, page) == Printable.PAGE_EXISTS, "Complete print operation from chosen starting level");
        original.restored("Different initial selection");
      }
      Snapshot original = new Snapshot(fixture);
      check(fixture.printable.getPageCount() == 3, "Prime page count before plan rendering failure");
      fixture.factory.plan.failAtLevel = home.getLevels().get(0);
      boolean failed = false;
      try { print(fixture.printable, 1); } catch (IllegalStateException injected) { failed = true; }
      check(failed, "Coordinated plan rendering failure propagates"); original.restored("Coordinated plan failure");
      HomePrintableComponent retry = new HomePrintableComponent(home, fixture.controller, FONT);
      failed = false;
      try { retry.getPageCount(); } catch (IllegalStateException injected) { failed = true; }
      check(failed, "Plan failure during page counting propagates"); original.restored("Plan page-count failure");
      fixture.factory.plan.failAtLevel = null;
      check(retry.getPageCount() == 3 && print(retry, 1) == Printable.PAGE_EXISTS, "Page count and rendering recover after plan failure");
      original.restored("Plan failure retry");
      boolean interrupted = false;
      Thread.currentThread().interrupt();
      try { print(fixture.printable, 0); } catch (PrinterException expected) { interrupted = true; }
      finally { Thread.interrupted(); }
      check(interrupted, "Interrupted print aborts with PrinterException before state mutation");
      original.restored("Interrupted operation");
    }
  }

  private static void customPaperPagination() throws Exception {
    try (Fixture fixture = new Fixture(home(2, 100))) {
      Snapshot original = new Snapshot(fixture);
      int normalCount = fixture.printable.getPageCount();
      PageFormat small = pageFormat(); Paper paper = small.getPaper();
      paper.setSize(612, 300); paper.setImageableArea(36, 36, 540, 228); small.setPaper(paper);
      Graphics2D graphics = new BufferedImage(612, 792, BufferedImage.TYPE_INT_RGB).createGraphics();
      try {
        check(fixture.printable.print(graphics, small, 0) == Printable.PAGE_EXISTS, "Print accepts caller-supplied short paper");
        int smallCount = fixture.printable.getPageCount();
        check(smallCount > normalCount, "Furniture page offsets are recounted for caller's paper size");
        fixture.factory.successfulPages.clear();
        for (int page = 0; page < smallCount; page++) {
          check(fixture.printable.print(graphics, small, page) == Printable.PAGE_EXISTS, "Short-paper page exists: " + page);
        }
        List<String> pages = fixture.factory.successfulPages;
        check(pages.get(pages.size() - 2).equals("plan:Level 0") && pages.get(pages.size() - 1).equals("plan:Level 1"),
            "Short-paper furniture pages still precede correctly indexed plan pages");
        check(print(fixture.printable, 0) == Printable.PAGE_EXISTS && fixture.printable.getPageCount() == normalCount,
            "Switching back to original paper restores correct pagination");
        original.restored("Paper-format changes");
      } finally { graphics.dispose(); }
    }
  }

  private static void samplePdf(Fixture fixture, String output) throws Exception {
    Home sample = fixture.home;
    try {
      check(fixture.factory.table.getRowCount() == 5, "Sample's saved expanded group is applied before export");
      fixture.preferences.setUnit(com.eteks.sweethome3d.model.LengthUnit.CENTIMETER);
      Snapshot original = new Snapshot(fixture);
      check(fixture.printable.getPageCount() == 4, "Sample preview count is one furniture page plus three plans");
      try (FileOutputStream stream = new FileOutputStream(output)) {
        new HomePDFPrinter(sample, fixture.preferences, fixture.controller, FONT).write(stream);
      }
      PdfReader pdf = new PdfReader(output);
      try {
        check(pdf.getNumberOfPages() == 4, "Actual application PDF exporter generates four pages for sample");
        check(pdf.getPageSize(1).getWidth() == 612 && pdf.getPageSize(1).getHeight() == 792, "Sample PDF uses reference Letter page dimensions");
      } finally { pdf.close(); }
      Set<String> rowLevels = new HashSet<String>();
      for (HomePieceOfFurniture row : fixture.factory.table.printedRows) rowLevels.add(row.getLevel().getName());
      check(rowLevels.equals(new HashSet<String>(Arrays.asList("Level 0", "Level 1", "Level 2"))), "Actual sample PDF's table includes furniture from all three levels");
      check(fixture.factory.table.printedColumns.contains(SortableProperty.LEVEL), "Sample PDF table includes Level");
      original.restored("Sample PDF export");
      Set<String> names = new HashSet<String>();
      for (HomePieceOfFurniture row : fixture.factory.table.printedRows) names.add(row.getName());
      check(names.contains("Computer workstation") && names.contains("Armchair") && names.contains("Bunk bed"),
          "Reference table's grouped and ungrouped furniture is included");
      System.out.println("PDF_EVIDENCE: " + output);
    } finally {
      fixture.close();
    }
  }

  public static void main(String[] args) throws Exception {
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
    Locale.setDefault(Locale.US);
    System.setProperty("com.eteks.sweethome3d.no3D", "true");
    temporary = Files.createTempDirectory("sweethome-print-regression-");
    String output = args.length == 0 ? temporary.resolve("task4-all-levels.pdf").toString() : args[0];
    SwingUtilities.invokeAndWait(() -> {
      try {
        paginationAndFiltering(); tableStateAndFailures(); plansAndLevelCounts(); selectedLevelsAndCoordinatedFailures(); customPaperPagination();
      } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    });
    // Let Swing deliver the frame's ancestor events, which restore saved group expansion,
    // before exporting. This matches opening the sample in the actual desktop application.
    final Fixture [] sample = new Fixture [1];
    SwingUtilities.invokeAndWait(() -> {
      try { sample [0] = new Fixture(new HomeFileRecorder().readHome("samples/MultiLevelHome.sh3d"), true); }
      catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    });
    SwingUtilities.invokeAndWait(() -> {
      try { samplePdf(sample [0], output); }
      catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    });
    final Fixture [] background = new Fixture [1];
    final Snapshot [] before = new Snapshot [1];
    SwingUtilities.invokeAndWait(() -> {
      try {
        background [0] = new Fixture(home(3, 3)); seedState(background [0]); before [0] = new Snapshot(background [0]);
      } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    });
    check(print(background [0].factory.plan, 0) == Printable.PAGE_EXISTS, "Background-thread plan printing completes");
    SwingUtilities.invokeAndWait(() -> {
      try {
        check(background [0].factory.plan.renderedOnDispatchThread, "Background print marshals level mutation and rendering onto the dispatch thread");
        before [0].restored("Background plan print"); background [0].close();
      } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    });
    backgroundInterruption(false); backgroundInterruption(true);
    System.out.println("ALL " + checks + " PRINTING REGRESSION CHECKS PASSED");
    System.exit(0);
  }
}
