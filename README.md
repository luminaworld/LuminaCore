# LuminaCore

<p align="center">
  <strong>ปลั๊กอินแกนกลางอเนกประสงค์แบบโมดูลาร์ (All-in-One Modular Core Plugin)</strong><br>
  พัฒนาด้วยภาษา Kotlin สำหรับเซิร์ฟเวอร์ Minecraft แพลตฟอร์ม Paper, Purpur และ <strong>Folia</strong> (เวอร์ชัน 1.21+)
</p>

---

## สรุปภาพรวมและจุดเด่นของระบบ (System Overview)

- **สถาปัตยกรรมโมดูลาร์ (Modular Architecture)**: รวบรวมระบบการทำงานมากกว่า **32 โมดูลย่อย** ไว้ในปลั๊กอินเดียว สามารถเปิด-ปิด หรือปรับแต่งการทำงานแต่ละโมดูลได้อย่างอิสระผ่านเมนูส่วนติดต่อผู้ใช้ (GUI) หรือไฟล์การตั้งค่า
- **รองรับ Folia และการจัดการทรัพยากรระดับสูง (Folia & Thread-Safe Architecture)**:
  - รองรับการทำงานบนแพลตฟอร์ม **Folia** (Regionized Multi-Threading) อย่างสมบูรณ์ โดยใช้ RegionScheduler, GlobalRegionScheduler และ AsyncScheduler อย่างถูกต้อง
  - พัฒนาด้วยภาษา Kotlin และระบบการจัดการงานแบบ Asynchronous เพื่อป้องกันการเกิด Blocking บน Main Thread
- **รองรับการจัดการปลั๊กอินแบบไดนามิกผ่าน PlugManX (PlugManX Full Compatibility)**:
  - ออกแบบวงจรการทำงาน (Lifecycle) ให้สามารถ Load, Unload, และ Reload ปลั๊กอินผ่าน **PlugManX** ได้อย่างสมบูรณ์แบบ ไร้ปัญหา Memory Leak
  - มีระบบ Dynamic Command Management ที่ช่วยถอนการลงทะเบียนคำสั่งและ Alias ออกจาก Bukkit CommandMap, KnownCommands และ Brigadier Root Node พร้อมสั่งซิงก์คำสั่งใหม่อย่างหมดจด
- **โครงสร้างความปลอดภัยและส่วนติดต่อผู้ใช้ (GUI & Anti-Exploit Framework)**:
  - **LuminaGUI**: โครงสร้างจัดการส่วนต่อประสานผู้ใช้ส่วนกลาง พร้อมระบบป้องกันการปั๊มไอเทมและช่องโหว่การจัดการช่องเก็บของ (Anti-Duplication & Inventory Exploit Protection)
  - **PacketBlockBreaker**: ระบบกระจายคิวการทำลายบล็อกตามลำดับ Tick (Staggered Queue) ช่วยลดภาระการประมวลผลของเซิร์ฟเวอร์ รองรับการเชื่อมต่อกับปลั๊กอินระบบอาชีพ **Jobs Reborn** และบันทึกสถิติผู้เล่น (Vanilla Statistics) ครบถ้วน
- **ระบบเศรษฐกิจและตลาดกลาง (Universal Economy & Market System)**:
  - **EconomyBridge**: ตัวกลางเชื่อมต่อระบบการเงิน รองรับทั้ง Vault, Vanilla Experience และระบบ Lumina Multi-Currency
  - **DynamicPricing**: ระบบคำนวณราคาซื้อขายสินค้าแบบแปรผันตามกลไกอุปสงค์และอุปทาน (Demand & Supply)
  - **Shop**: ระบบร้านค้าส่วนติดต่อผู้ใช้ (GUI Shop) รองรับการซื้อขายปลีก, การระบุจำนวนสินค้า, เมนูขายไอเทมทั้งตัว (`SellGUI`) และรองรับระบบหลายภาษา (ภาษาไทยและภาษาอังกฤษ)
- **ระบบมินิเกมและกิจกรรม (Integrated Minigames)**:
  - **CaptureTheFlag**: ระบบยึดครองพื้นที่ชิงธง พร้อมการแสดงผลขอบเขตและระบบคูลดาวน์
  - **ChatGames**: มินิเกมตอบคำถาม, พิมพ์เร็ว, แก้สมการ และการแข่งขันภารกิจภายในเกม พร้อมระบบจ่ายรางวัลอัตโนมัติ
- **ระบบความปลอดภัยและการตรวจสอบ (Security & Audit Monitoring)**:
  - **AntiOp**: ตรวจสอบ Whitelist ผู้ดูแลระบบ, บังคับยืนยันตัวตนผ่านรหัสผ่านด้วย Native Dialog UI ในเกม และระงับการยกระดับสิทธิ์ผ่าน Console
  - ส่งบันทึกเหตุการณ์สำคัญผ่าน **Discord Webhook** แบบเรียลไทม์ (BlockNotifier, CreativeMonitor, RconMonitor, UnknownCommand, UltimateAutoRestart)
- **ระบบการตั้งค่าส่วนบุคคลของผู้เล่น (Player Preferences)**:
  - เมนูคำสั่ง `/settings` ให้ผู้เล่นสามารถเปิด-ปิดฟังก์ชันช่วยเหลือและเอฟเฟกต์เฉพาะตัวได้ พร้อมบันทึกข้อมูลลงฐานข้อมูล MySQL หรือ SQLite
- **ระบบการสร้างแพ็กเกจอิสระ (Standalone Build System)**: รองรับการแยกบิลด์แต่ละโมดูลย่อยออกมาเป็นไฟล์ปลั๊กอินเดี่ยว (`.jar`) ตามความต้องการ

---

## ข้อมูลผู้พัฒนา (Developer Information)

- **ผู้พัฒนา**: **Loma0531**
- **คลังข้อมูลโปรเจกต์ (Repository)**: [https://github.com/luminaworld/LuminaCore](https://github.com/luminaworld/LuminaCore)

---

## หมวดหมู่ของโมดูลทั้งหมด (Module Categories)

| หมวดหมู่ | จำนวนโมดูล | รายละเอียดโดยสังเขป |
| :--- | :---: | :--- |
| **System** | 10 โมดูล | ระบบโครงสร้างพื้นฐาน, ความปลอดภัย, ฐานข้อมูล, การเงิน, บอทจำลอง, การรีสตาร์ทอัตโนมัติ และ Discord Webhook |
| **Activate** | 9 โมดูล | ระบบสั่งงานและสลับสถานะการทำงานด้วยคีย์ลัดการย่อตัว (Shift) พร้อมแถบแสดงสถานะความคืบหน้า |
| **Features** | 11 โมดูล | ระบบอำนวยความสะดวกในการเล่น, การแปลงสภาพบล็อกทางกายภาพ, การป้องกันไอเทมสูญหาย และระบบร้านค้า GUI |
| **Minigames** | 2 โมดูล | มินิเกมยึดพื้นที่ชิงธง (CaptureTheFlag) และมินิเกมตอบคำถาม/แข่งขันภารกิจในช่องแชท (ChatGames) |

อ่านรายละเอียดและคู่มือการใช้งานของโมดูลทั้งหมดได้ที่ [FEATURES.md](FEATURES.md)

---

## การติดตั้งและการใช้งานเบื้องต้น (Installation & Setup)

1. นำไฟล์ `LuminaCore.jar` ไปติดตั้งในไดเรกทอรี `plugins/` ของเซิร์ฟเวอร์
2. เริ่มการทำงานของเซิร์ฟเวอร์ ปลั๊กอินจะสร้างไดเรกทอรีและไฟล์การตั้งค่าเริ่มต้นแยกตามหมวดหมู่ใน `plugins/LuminaCore/`
3. **คำสั่งหลักสำหรับผู้ดูแลระบบ**:
   - `/luminacore` — เปิดหน้าต่าง GUI เพื่อจัดการเปิดหรือปิดโมดูลย่อยทั้งหมดแบบเรียลไทม์
   - `/luminacore reload` — โหลดไฟล์การตั้งค่าของทุกโมดูลใหม่โดยไม่ต้องเริ่มเซิร์ฟเวอร์ใหม่
   - `/settings` หรือ `/setting` — เปิดหน้าต่างจัดการการตั้งค่าส่วนบุคคลของผู้เล่น

---

## วิธีการบิลด์ปลั๊กอินจากซอร์สโค้ด (Building the Plugin)

โปรเจกต์ใช้ระบบ **Gradle Wrapper** ในการจัดการการบิลด์ โดยแบ่งออกเป็น 2 รูปแบบ:

### 1. การบิลด์แบบรวมทุกระบบ (Full Build)
บิลด์รวมทุกโมดูลเป็นปลั๊กอินหลักไฟล์เดียว:

```bash
./gradlew build
```
*(ไฟล์ JAR หลักจะถูกสร้างขึ้นที่ `build/libs/LuminaCore.jar`)*

---

### 2. การบิลด์แยกเฉพาะโมดูลย่อย (Standalone Build)
สามารถแยกบิลด์แต่ละโมดูลย่อยออกมาเป็นไฟล์ปลั๊กอินเดี่ยวที่ทำงานได้อย่างอิสระ:

- **บิลด์ทุกโมดูลย่อยแยกไฟล์พร้อมกันทั้งหมด**:
  ```bash
  ./gradlew jarAllStandalone
  ```
- **บิลด์เฉพาะโมดูลที่ต้องการ**:
  ```bash
  ./gradlew jar<ModuleName>
  ```
  *ตัวอย่างคำสั่ง:*
  - `./gradlew jarAntiOp`
  - `./gradlew jarTreeCapitator`
  - `./gradlew jarVeinMiner`
  - `./gradlew jarShop`

---

## การข้ามการตรวจสอบใบอนุญาต (Bypass License Verification)

สำหรับการใช้งานภายในหรือในสภาพแวดล้อมการพัฒนา (Development Environment) สามารถข้ามขั้นตอนการตรวจสอบใบอนุญาตได้ดังนี้:

1. เปิดไฟล์ [LicenseManager.kt](file:///home/loma/Desktop/Luminaris/plugin/LuminaCore_Dev/src/main/kotlin/core/luminaworld/license/LicenseManager.kt)
2. กำหนดค่าตัวแปร `BYPASS` ให้เป็น `true`:
   ```kotlin
   private const val BYPASS = true
   ```
3. ดำเนินการบิลด์ปลั๊กอินตามปกติ ระบบจะข้ามขั้นตอนการตรวจสอบใบอนุญาตโดยสมบูรณ์
