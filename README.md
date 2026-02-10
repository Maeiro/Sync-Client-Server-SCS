## Sync Client Server (SCS)

**Sync Client Server (SCS)** is a NeoForge mod designed to keep client mods and configurations synchronized with a server-provided modpack, reducing version mismatches and manual mod management.

With a single **in-game Update button** directly in the multiplayer server list, players can download the mods and configs required by the server in a simple and reliable way.

---

### 📸 Preview

![In-game printscreen](https://cdn.modrinth.com/data/cached_images/db8c26c778d41e985b4b40c7b6f7a413436ecb98.png)

---

### 🎯 Goal
SCS aims to simplify server administration and improve player experience by making modpack updates **clear, predictable, and user-friendly**, without requiring external launchers or manual file management.

---

## ✨ What SCS Can Do

- In-game **Update button** in the multiplayer server list  
- Confirmation screen with update summary and **Clear cache** option  
- **modId-based synchronization** to prevent duplicated or outdated mods  
- Optional synchronization of `/config` alongside `/mods`  
- Optional **mirror mode** for `/mods` and `/config`  
- Server-enforced removal of specific client mods  
- Per-server isolated cache  
- Built-in lightweight file server to host `mods.zip` and `config.zip`

> ⚠️ **Important:**  
> - **Mod updates require a game restart** to take effect  
> - **Config updates do NOT require a restart** and are applied immediately

---

## 🎮 Client Usage (Simple & Automatic)

The client workflow is intentionally minimal.

### 1️⃣ Add the server
- Add the server normally in the **Multiplayer** screen
- The **Download URL** field is automatically filled using the server address and port
- You may override it manually if needed

Example:
```
http://myserver.com:25566
```

### 2️⃣ Update the client
1. Select the server in the list
2. Click **Update**
3. Review the summary and confirm

That’s it.  
Restart the game **only if mods were updated**.

### 🧹 Clear Cache
Use **Clear cache** from the confirmation screen if:
- A download fails
- The client becomes out of sync
- ZIP contents changed but no update is triggered
- Other unexpected behaviors

Cache is isolated per server and safe to clear.

---

## 🖥️ Server Usage (Admin Guide)

### 1️⃣ Install and start
- Install SCS on the server
- Start the server once to generate the config file

---

### 2️⃣ Common Configuration
Config file:
```
config/scs-common.toml
```

Default values:
```toml
fileServerPort = 25566
updateConfig = true
mirrorMods = false
mirrorConfig = false
```

**Explanation:**
- `fileServerPort`  
  Port used by the built-in file server (default: `25566`)
- `updateConfig`  
  Enables client config updates (enabled by default)
- `mirrorMods` / `mirrorConfig`  
  Disabled by default. When enabled, client folders are forced to exactly match the ZIP files

Because updateConfig is enabled by default, server admins can push new configuration changes to clients, such as enabling or disabling mirror options.

---

### 3️⃣ Creating the update packages

#### Option A: Using in-game commands
On the server, run:
```
/scs save-mods
/scs save-config
```

This generates:
- `mods.zip`
- `config.zip`

---

#### Option B: Manual ZIP creation (advanced / recommended for control)
Admins may **manually create or edit**:
- `mods.zip`
- `config.zip`

This allows:
- Shipping only specific mods or configs
- Full control over update contents

Both methods are fully supported.

---

### 4️⃣ Removing client mods (server-enforced)
To force the removal of specific client mods:

1. Create a file named:
```
modsToRemoveFromTheClient.json
```

2. Place it **inside `mods.zip`**
3. List the `.jar` filenames to remove

Example:
```json
[
  "examplemod1.jar",
  "examplemod2.jar"
]
```

These mods will be deleted from the client during the update.

---

### 5️⃣ Built-in File Server
When the server is running, SCS automatically hosts:
- `mods.zip`
- `config.zip`

On:
```
<server-ip>:fileServerPort
```

Default:
```
<server-ip>:25566
```

No external web server is required.

---

### 📦 Modpack Usage
Feel free to include this mod in your modpack — credits are appreciated but not required.

---

### ❤️ Credits & Fork Notice
This project is a **fork of the mod _MMMMM_** by **Place-Boy**.  
Original project: https://www.curseforge.com/minecraft/mc-mods/mmmmm  

All credit for the original concept and foundation goes to the original author.
