# Minecraft World Downloader
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


