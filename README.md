# 📱 Android 拓竹 RFID 标签识别 & 库存管理应用

一款可直接使用 **Android 手机**识别 **拓竹（Bambu）RFID 标签**，并支持 **耗材库存管理** 的应用程序。

------

## ✅ 功能介绍

- 📌 **读取拓竹 RFID 标签信息**
- 📦 **耗材库存管理（入库 / 出库 / 统计）**
- 🗂️ **支持导入耗材配置文件，显示更完整的耗材信息**
- ⏺ **复制与定义标签数据**

------

## ⚠️ 数据库与配置文件位置（务必备份）

应用数据库与耗材信息配置文件存放在：

```
Android/data/com.m0h31h31.bamburfidreader/files
```

📌 **注意：升级 / 换机 / 清理缓存前请先备份该目录内容！**

------

## 🎨 耗材信息配置文件来源（官方 Bambu Studio）

耗材配置文件来自拓竹官方 **Bambu Studio**，文件名：

```
filaments_color_codes.json
```

可在以下路径找到：

### 方式 1：Bambu Studio 安装目录

```
Bambu Studio\resources\profiles\BBL\filament\filaments_color_codes.json
```

### 方式 2：用户配置目录（Windows）

```
C:\Users\<username>\AppData\Roaming\BambuStudio\system\BBL\filament\filaments_color_codes.json
```

当官方耗材数据更新时替换Android/data/com.m0h31h31.bamburfidreader/files的filaments_color_codes.json已更新耗材数据

------

## 🧩 MakerWorld 支持（Logo 模型）

我在 MakerWorld 发布了一个 **Logo 模型**，欢迎助力 & 打印支持一下 🙌
 （可缩小打印，减少耗材浪费并节省打印时间）

🔗 **模型链接：**
 https://makerworld.com.cn/zh/models/2020787

------

## 🖼️ 应用截图

![应用截图1](https://github.com/m0h31h31/BambuRfidReader/blob/master/img/1.jpg)
 ![应用截图2](https://github.com/m0h31h31/BambuRfidReader/blob/master/img/2.jpg)

------

## 🗺️ 开发计划 / 进度

| 功能                     | 状态       |
| ------------------------ | ---------- |
| 读取标签                 | ✅ 已完成   |
| 库存管理                 | ⏳ 部分完成 |
| 复制标签（支持 AMS）     | ⏳ 未完成   |
| 自定义标签（不支持 AMS） | ⏳ 未完成   |

------

## 📚 参考资料

- RFID 标签指南（Bambu Research Group）
   https://github.com/Bambu-Research-Group/RFID-Tag-Guide
