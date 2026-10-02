# Street Abbreviator & Polish Name Normalization Engine

## 1. Overview
The `StreetAbbreviator` (`janush.tech.whereami.StreetAbbreviator`) is a modular, locale-aware street name compression and abbreviation engine. Its primary objective is providing ultra-fast, legible, and compact street names on restricted display surfaces such as **Android Auto**, car head units, small widgets, and compact UI modes, while strictly preventing corruption of historical, institutional, or religious non-person street names.

## 2. Abbreviation Rules & Architecture

### A. Polish Street Names (`PL`)
1. **Prefix Normalization**:
   - `ulica ` / `ul. ` $\to$ `ul. `
   - `aleja ` / `aleje ` / `al. ` $\to$ `al. `
   - `plac ` / `pl. ` $\to$ `pl. `
   - `osiedle ` / `os. ` $\to$ `os. `
2. **Honorific & Title Abbreviations (`PL_TITLES`)**:
   Honorific, clerical, and military titles preceding a name are abbreviated to standard Polish cartographic conventions:
   - `Generała` / `Generał` $\to$ `Gen.`
   - `Księdza` / `Ksiądz` $\to$ `Ks.`
   - `Biskupa` / `Biskup` $\to$ `Bp.`
   - `Arcybiskupa` / `Arcybiskup` $\to$ `Abp.`
   - `Kardynała` / `Kardynał` $\to$ `Kard.`
   - `Świętego` / `Świętej` / `Świętych` $\to$ `Św.`
   - `Pułkownika` / `Pułkownik` $\to$ `Płk.`
   - `Majora` / `Major` $\to$ `Mjr.`
   - `Kapitana` / `Kapitan` $\to$ `Kpt.`
   - `Porucznika` / `Porucznik` $\to$ `Por.`
   - `Marszałka` / `Marszałek` $\to$ `Marsz.`
   - `Profesora` / `Profesor` $\to$ `Prof.`
   - `Doktora` / `Doktor` $\to$ `Dr.`
   - `Prezydenta` / `Prezydent` $\to$ `Prez.`
3. **Given Name Abbreviation Dictionary (`PL_GIVEN_NAMES`)**:
   - Polish given names (in both nominative and genitive forms, e.g. `Józef`/`Józefa`, `Stefania`/`Stefanii`, `Jan`/`Jana`) preceding a surname are safely abbreviated to their single capital initial and dot (`J. Lompy`, `S. Sempołowskiej`).
   - A given name is **only** abbreviated if at least one subsequent token (the surname) remains.
   - Institutional or collective names (such as `Wojska Polskiego`, `Armii Krajowej`, `Bohaterów Monte Cassino`, `Powstańców Śląskich`) are deliberately excluded from the given names dictionary to preserve complete historical designations.
   - Roman numerals are protected: designations like `Jana III Sobieskiego` preserve the full given name and numeral.
4. **Major Corridor & Highway Protection**:
   - Canonical national road designations (`DK52`, `DW946`, `A4`, `S7`, `E77`) are never shortened or stripped.
   - Suffix corridor annotations (e.g. `ul. Krakowska (DK52)`) preserve the corridor code intact while abbreviating the local street prefix.

### B. International Rule Sets
- **English (`EN`)**: Abbreviates street types (`Street` $\to$ `St`, `Avenue` $\to$ `Ave`, `Boulevard` $\to$ `Blvd`, `Road` $\to$ `Rd`) and titles (`Doctor` $\to$ `Dr.`, `Saint` $\to$ `St.`).
- **German (`DE`)**: Suffix compression (`-straße` / `-strasse` $\to$ `str.`, `Platz` $\to$ `pl.`, `Gasse` $\to$ `g.`).

## 3. Android Auto Integration
In Android Auto (`AutoMediaService.kt`), compact street names are automatically enforced by default:
- Full: `ulica Stefanii Sempołowskiej` $\to$ Abbreviated: `ul. S. Sempołowskiej`
- Full: `ulica Księdza Stanisława Stojałowskiego` $\to$ Abbreviated: `ul. Ks. S. Stojałowskiego`
- Full: `ulica Józefa Lompy 10` $\to$ Abbreviated: `ul. J. Lompy 10`
