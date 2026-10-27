# Minecraft World Downloader

![Downloading Gif](docs/downloading.gif)

A fork of [mircokroon/minecraft-world-downloader](https://github.com/mircokroon/minecraft-world-downloader) - 
A Minecraft world downloader that works as a proxy server between the client and the server to read & save chunk 
data as you explore it. Download multiplayer worlds by connecting to them and walking around. Perfect for
historical archival of servers, preserving your hundred-hour builds, or just for fun. This fork adds quality of
life enhancements from the upstream repo that has since been abandoned.

**NOTE:** Every chunk you want to download has to be in your render distance as you explore them. This, 
nor any other tool, can download chunks that are not sent to your client by the server.

> [!WARNING]
> This tool is intended for personal and research purposes only. Downloading worlds from servers without permission 
> may violate the server's rules or terms of service. Always check with the server owner before using this tool on their server.

## Features
- Real-time chunks downloading as you explore the world.
- Real-time progress display with rendered chunk preview. You can see where you've explored, where you are now, and where you still need to visit.
- SOCKS5 proxy support between the downloader client and the multiplayer server. This allows you to use a VPN to safely explore servers without revealing your IP address.
- Map overlays: import region outlines, borders and points of interest from a server's BlueMap or Dynmap and see them drawn over the downloaded chunks.

## How it Works
1. You run the Minecraft World Downloader (this repo) on your computer, which starts a local proxy server.
2. You configure the downloader to point to the multiplayer server you want to download from, providing your Minecraft authentication details.
3. You configure your Minecraft client to connect to the local proxy server instead of the multiplayer server.
4. The downloader intercepts the data sent between your Minecraft client and the multiplayer server, saving the chunk data to your local disk as you explore the world.
5. The multiplayer server sees your Minecraft client as a normal player, so you can explore the world and download it without any special permissions.

## Supported Versions
The following multiplayer server versions are supported by this tool. If the version you are trying to download 
is not listed, you may be able to run [ViaFabricPlus](https://github.com/ViaVersion/ViaFabricPlus) in your client
to downgrade the protocol version to one that is supported. This relies on server-side support for older versions,
typically from servers running [ViaBackwards](https://github.com/ViaVersion/ViaBackwards).

| Minecraft version    | Protocol | Data version |
|----------------------|---------:|-------------:|
| 26.3                 |      777 |         5023 |
| 26.2                 |      776 |         4903 |
| 26.1, 26.1.1, 26.1.2 |      775 |         4790 |
| 1.21.11              |      774 |         4671 |
| 1.21.9, 1.21.10      |      773 |         4556 |
| 1.21.7, 1.21.8       |      772 |         4440 |
| 1.21.6               |      771 |         4435 |
| 1.21.5               |      770 |         4325 |
| 1.21.4               |      769 |         4189 |
| 1.21.2, 1.21.3       |      768 |         4080 |
| 1.21, 1.21.1         |      767 |         3953 |
| 1.20.5, 1.20.6       |      766 |         3839 |
| 1.20.3, 1.20.4       |      765 |         3700 |
| 1.20.2               |      764 |         3578 |
| 1.20, 1.20.1         |      763 |         3463 |
| 1.19.4               |      762 |         3337 |
| 1.19.3               |      761 |         3218 |
| 1.19 - 1.19.2        |      759 |         3105 |
| 1.18 - 1.18.2        |      757 |         2860 |
| 1.17, 1.17.1         |      755 |         2724 |
| 1.16.2 - 1.16.5      |      701 |         2578 |
| 1.15.2               |      550 |         2200 |
| 1.14.1 - 1.14.4      |      440 |         1901 |
| 1.13.2               |      341 |         1444 |
| 1.12.2               |      317 |         1132 |

## Menus
Target server the proxy should connect to and download chunks from:
![](docs/connection.png)

For servers that support it, asking for extended render distance chunks increases the amount of chunks around you being 
requested and downloaded on your behalf, regardless of your client settings.
![](docs/general.png)

Output:
![](docs/world.png)

The proxy connects directly to the remote Minecraft server and must have your Minecraft account access:
![](docs/authentication.png)

Optionally, the proxy can connect to the remote Minecraft server through a SOCKS5 proxy.
![](docs/proxy.png)

## Map overlays
Many servers publish a web map with region outlines, world borders, rail lines and points of interest. You can import
those shapes into the downloader's map, so you can see which chunks still need to be visited to cover a town or a
claim. The shapes are drawn on top of the chunk map, the shape under the cursor is highlighted and its name is shown
next to the coordinates.

Right-click the map and open **Map overlays**:
- **Import overlay file...** reads a BlueMap or Dynmap marker file, or a file in the simple format described below.
  You then pick the dimension the shapes belong to (guessed from the file name) and which marker sets to show.
- **Show overlays** turns all overlays on or off.
- **Chunk grid in hovered shape** draws the chunk borders inside the shape under the cursor when zoomed in, to show
  exactly which chunks it covers.
- Every imported file gets its own submenu to hide it, hide single marker sets, or remove it.

Imported files are remembered between sessions. The downloader keeps a reference to the file, so leave it where it
is or import it again after moving it. Right-clicking on a shape also offers **Copy shape name**.

### Getting the marker file from a web map
Open the server's web map in your browser, then download the marker file with the browser or a tool like `curl`.
The browser's developer tools (Network tab, filter on `markers`) show the exact address if the ones below don't work.

**BlueMap** stores the markers of each map at `maps/<map>/live/markers.json`, for example
`http://mc.example.com:8100/maps/world/live/markers.json`. The map ids are listed in `settings.json` in the same
place as the web map (`http://mc.example.com:8100/settings.json`, under `maps`). Each map, such as `world`,
`world_nether` or `world_the_end`, has its own markers file.

**Dynmap** stores the markers of each world at `tiles/_markers_/marker_<world>.json`, for example
`http://mc.example.com:8123/tiles/_markers_/marker_world.json`. The world names are listed in
`up/configuration` (`http://mc.example.com:8123/up/configuration`, under `worlds`). Servers that host Dynmap
on a separate web server may use a different `tiles` path, which the Network tab shows.

Plugins that add regions to the map (WorldGuard, GriefPrevention, Towny, world borders and so on) store their shapes
in these same files, as their own marker sets.

### Simple overlay format
To draw your own shapes, write a JSON file with a `shapes` list. Coordinates are block `[x, z]` pairs.

```json
{
  "shapes": [
    { "type": "rect", "from": [-64, -64], "to": [63, 63], "label": "Spawn", "color": "#00ff00" },
    { "type": "polygon", "points": [[6640, 7690], [6841, 7690], [6841, 7891], [6640, 7891]], "label": "Old town" },
    { "type": "circle", "center": [1000, -2000], "radius": 250, "label": "Crater", "set": "Landmarks" },
    { "type": "line", "points": [[0, 0], [0, 5000]], "label": "North rail", "width": 3 },
    { "type": "point", "at": [1200, 340], "label": "Library", "set": "Landmarks" }
  ]
}
```

Optional fields for every shape:
- `label`: name shown on the map.
- `set`: groups shapes into sets that can be hidden separately (default `Shapes`).
- `color`: outline colour, for example `#ff0000` (default yellow).
- `fill`: fill colour including transparency, for example `#ff000040` (default is the outline colour, mostly transparent).
- `width`: outline width in pixels, 1 to 8 (default 2).

Entries that can't be read are skipped, and the number of skipped entries is shown after importing.
