package gui;

import config.Config;
import game.data.WorldManager;
import game.data.chunk.Chunk;
import game.data.chunk.ChunkBinary;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import game.data.region.McaFile;
import gui.images.ImageMode;
import gui.images.RegionImageHandler;
import gui.overlay.OverlayImportDialog;
import gui.overlay.OverlayLayer;
import gui.overlay.OverlayManager;
import gui.overlay.OverlaySet;
import gui.overlay.OverlayShape;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;

public class RightClickMenu extends ContextMenu {
    final static String PROMPT_PAUSE = "Pause chunk saving";
    final static String PROMPT_RESUME = "Resume chunk saving";

    private final MenuItem pauseMenuItem;
    private final GuiMap handler;
    private final Menu overlayMenu = new Menu("Map overlays");
    private final MenuItem copyShapeItem;
    private OverlayShape clickedShape;

    final List<MenuItem> renderModes = List.of(
        construct("Automatic", e -> setRenderMode(null, e)),
        construct("Surface", e -> setRenderMode(ImageMode.NORMAL, e)),
        construct("Caves", e -> setRenderMode(ImageMode.CAVES, e))
    );

    private void setRenderMode(ImageMode mode, Event e) {
        renderModes.forEach(el -> el.setDisable(false));
        MenuItem clicked = (MenuItem) e.getTarget();
        clicked.setDisable(true);

        RegionImageHandler.setOverrideMode(mode);
    }

    public RightClickMenu(GuiMap handler) {
        this.handler = handler;
        List<MenuItem> menu = this.getItems();

        pauseMenuItem = construct(PROMPT_PAUSE, event -> {
            MenuItem item =  ((MenuItem) event.getTarget());
            if (WorldManager.getInstance().isPaused()) {
                WorldManager.getInstance().resumeSaving();
                item.setText(PROMPT_PAUSE);
            } else {
                WorldManager.getInstance().pauseSaving();
                item.setText(PROMPT_RESUME);
            }
            handler.setStatusMessage("");
        });
        menu.add(pauseMenuItem);

        menu.add(construct("Delete all downloaded chunks", e -> {
            Alert alert = new Alert(Alert.AlertType.NONE,
                    "Are you sure you want to delete all downloaded chunks? This cannot be undone.",
                    ButtonType.CANCEL, ButtonType.YES
            );
            GuiManager.addIcon((Stage) alert.getDialogPane().getScene().getWindow());
            alert.setTitle("Confirm delete");
            alert.getDialogPane().getStylesheets().add(getClass().getResource("/ui/dark.css").toExternalForm());
            alert.showAndWait();

            if (alert.getResult() == ButtonType.YES) {
                WorldManager.getInstance().deleteAllExisting();
            }
        }));


        menu.add(new SeparatorMenuItem());

        menu.add(construct("Redraw nearby chunks", e -> {
            Coordinate2D region = handler.getCursorCoordinates().globalToRegion();
            new Thread(() -> WorldManager.getInstance().drawExistingChunks(region)).start();
        }));

        menu.add(construct("Redraw region", e -> {
            Coordinate2D region = handler.getCursorCoordinates().globalToRegion();
            handler.getRegionHandler().resetRegion(region);
            new Thread(() -> WorldManager.getInstance().drawExistingRegion(region)).start();
        }));


        menu.add(construct("Copy coordinates", e -> {
            Coordinate2D coords = handler.getCursorCoordinates();
            String coordsString = String.format("%d ~ %d", coords.getX(), coords.getZ());
            StringSelection selection = new StringSelection(coordsString);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
        }));

        copyShapeItem = construct("Copy shape name", e -> {
            if (clickedShape != null) {
                StringSelection selection = new StringSelection(clickedShape.getLabel());
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            }
        });
        menu.add(copyShapeItem);

        menu.add(new SeparatorMenuItem());

        ImageMode current = RegionImageHandler.getOverrideMode();


        menu.add(new Menu("Render mode", null, renderModes.toArray(new MenuItem[0])));
        menu.add(overlayMenu);
        menu.add(construct("Settings", e -> GuiManager.loadWindowSettings()));

        menu.add(construct("Save & Exit", e -> {
            GuiManager.saveAndExit();
        }));

        if (Config.isInDevMode()) {
            addDevOptions(menu, handler);
        }
    }

    /**
     * The pause item's label only updates itself when clicked, so it can go stale when saving is
     * paused/resumed from elsewhere (e.g. an auto-pause on world switch). Call before showing the menu.
     */
    public void refreshPauseLabel() {
        pauseMenuItem.setText(WorldManager.getInstance().isPaused() ? PROMPT_RESUME : PROMPT_PAUSE);
    }

    /**
     * The overlay submenu lists the imported files, so it's rebuilt every time the menu is shown.
     *
     * @param shape the overlay shape under the cursor, if any
     */
    public void refreshOverlayItems(OverlayShape shape) {
        clickedShape = shape;
        copyShapeItem.setVisible(shape != null && !shape.getLabel().isEmpty());

        OverlayManager manager = handler.getOverlayManager();
        List<MenuItem> items = overlayMenu.getItems();
        items.clear();

        MenuItem importItem = new MenuItem("Import overlay file...");
        importItem.setOnAction(e -> {
            String status = OverlayImportDialog.show(getOwnerWindow(), manager, handler.getDimension());
            if (status != null) {
                handler.flashStatusMessage(status);
            }
        });
        items.add(importItem);

        CheckMenuItem showItem = new CheckMenuItem("Show overlays");
        showItem.setSelected(Config.showOverlays());
        showItem.setOnAction(e -> {
            Config.setShowOverlays(showItem.isSelected());
            Config.save();
        });
        items.add(showItem);

        CheckMenuItem gridItem = new CheckMenuItem("Chunk grid in hovered shape");
        gridItem.setSelected(Config.overlayChunkGrid());
        gridItem.setOnAction(e -> {
            Config.setOverlayChunkGrid(gridItem.isSelected());
            Config.save();
        });
        items.add(gridItem);

        if (manager.getLayers().isEmpty()) {
            return;
        }
        items.add(new SeparatorMenuItem());

        for (OverlayLayer layer : List.copyOf(manager.getLayers())) {
            Menu layerMenu = new Menu(String.format("%s (%s, %d shapes)",
                layer.getName(), OverlayLayer.dimensionLabel(layer.getDimension()), layer.countShapes()));

            CheckMenuItem layerShow = new CheckMenuItem("Show");
            layerShow.setSelected(layer.isVisible());
            layerShow.setOnAction(e -> {
                layer.setVisible(layerShow.isSelected());
                manager.save();
            });
            layerMenu.getItems().addAll(layerShow, new SeparatorMenuItem());

            for (OverlaySet set : layer.getSets()) {
                CheckMenuItem setItem = new CheckMenuItem(set.getLabel() + " (" + set.getShapes().size() + ")");
                setItem.setSelected(layer.isSetVisible(set));
                setItem.setOnAction(e -> {
                    layer.setSetVisible(set, setItem.isSelected());
                    manager.save();
                });
                layerMenu.getItems().add(setItem);
            }

            MenuItem removeItem = new MenuItem("Remove");
            removeItem.setOnAction(e -> manager.remove(layer));
            layerMenu.getItems().addAll(new SeparatorMenuItem(), removeItem);

            items.add(layerMenu);
        }

        MenuItem removeAll = new MenuItem("Remove all overlays");
        removeAll.setOnAction(e -> manager.clear());
        items.add(new SeparatorMenuItem());
        items.add(removeAll);
    }

    private void addDevOptions(List<MenuItem> menu, GuiMap handler) {
        menu.add(new SeparatorMenuItem());

        menu.add(construct("Write chunk", e -> {

            CoordinateDim2D chunk = handler.getCursorCoordinates().globalToChunk().addDimension(Dimension.OVERWORLD);
            CoordinateDim2D region = handler.getCursorCoordinates().globalToRegion().addDimension(Dimension.OVERWORLD);

            ChunkBinary cb = new McaFile(region).getChunkBinary(chunk);

            String filename = "chunkdata.bin";
            FileOutputStream f = new FileOutputStream(filename);
            ObjectOutputStream o = new ObjectOutputStream(f);
            o.writeObject(cb);

            System.out.println("Written chunk " + chunk + " to " + filename);
        }));

        menu.add(construct("Write all chunks as text", e -> {
           Config.toggleWriteChunkNbt();
        }));

        menu.add(construct("Print stats", e -> {
            int regions = WorldManager.getInstance().countActiveRegions();
            int binaryChunks = WorldManager.getInstance().countActiveBinaryChunks();
            int unpasedChunks = WorldManager.getInstance().countQueuedChunks();
            int chunks = WorldManager.getInstance().countActiveChunks();
            int extendedChunks = WorldManager.getInstance().countExtendedChunks();
            int entities = WorldManager.getInstance().getEntityRegistry().countActiveEntities();
            int players = WorldManager.getInstance().getEntityRegistry().countActivePlayers();
            int maps = WorldManager.getInstance().getMapRegistry().countActiveMaps();
            String imageStats = handler.imageStats();

            System.out.printf("Statistics:" +
                            "\n\tActive regions: %d" +
                            "\n\tActive binary chunks: %d" +
                            "\n\tActive unparsed chunks: %d" +
                            "\n\tActive chunks: %d" +
                            "\n\tActive extended chunks: %d" +
                            "\n\tActive entities: %d" +
                            "\n\tActive players: %d" +
                            "\n\tActive maps: %d" +
                            "\n\tActive region images: %s" +
                            "\n",
                    regions, binaryChunks, unpasedChunks, chunks, extendedChunks, entities, players, maps, imageStats);
        }));

        menu.add(construct("Print chunk events", e -> {
            Chunk.printEventLog(handler.getCursorCoordinates().globalToChunk().addDimension(Dimension.OVERWORLD));
        }));
    }

    private MenuItem construct(String name, boolean isDisabled, HandleError handler) {
        MenuItem item = construct(name, handler);
        item.setDisable(isDisabled);
        return item;
    }

    private MenuItem construct(String name, HandleError handler) {
        MenuItem item = new MenuItem(name);
        item.addEventHandler(EventType.ROOT, handler);
        return item;
    }
}

interface HandleError extends EventHandler<Event> {
    @Override
    default void handle(Event event) {
        try {
            handleErr(event);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void handleErr(Event event) throws IOException;
}