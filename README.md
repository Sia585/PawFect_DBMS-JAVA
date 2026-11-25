# Pawfect

A local pet care manager with a Java HTTP backend and SQLite database. It stores pet profiles and care records on the computer running the server.

## Run it

Install Java 17 or newer and Maven, open a terminal in the `backend` directory, and run:

```sh
mvn compile exec:java
```

Then open <http://localhost:8080>. The SQLite database file (`pawfect.db`) is created in the `backend` directory the first time the server starts. Stop the server with Ctrl+C. To use a different port, set `PAWFECT_PORT` before starting it.

## Demo data (optional, one time)

To try Pawfect with three fictional pets (Milo, Luna and Mochi) that have care records, overdue and upcoming reminders and weight history, stop Pawfect and run this once from the `backend` directory:

```sh
mvn compile exec:java@demo-data
```

It only works on a database with no pets, care records or weight measurements; otherwise it changes nothing and says so. It is never run automatically. Dates are relative to the day you run it.

## Features

**Original**

- Pet profiles with species, breed, birthday, sex, weight, vet, emoji, and notes
- Records for vaccination, medication, vet visits, feeding, grooming, weight, and other care
- Due dates grouped into overdue, soon, and upcoming reminders
- Per-pet care timelines, dashboard summaries, search, and filters
- Export a pet's profile and care history to CSV
- SQLite foreign keys and transactional saves for pet and care data

**PawFect 2.0**

- Structured weight tracking: dated measurements entered in kg, lb or g, stored as integer grams, with an SVG trend chart and summary cards (the CSV export includes the weight history)
- Reviewed import of old Weight care records: clear notes are pre-selected, ambiguous ones are never guessed, and care records are never changed
- Pet photographs (JPEG or PNG): checked, resized and stored as files in `backend/photos`, with the emoji as a fallback
- Light, dark and system themes
- Custom species and breed suggestions when adding a pet
- Versioned schema migrations (`PRAGMA user_version`, currently 3). Before upgrading a database that already holds data, a verified backup is written to `backend/backups`, and a database from a newer version is refused
- Revision-based conflict detection: a stale save from another tab is refused (HTTP 409) and nothing is overwritten
- A request guard that checks the Host and Origin headers and limits request sizes

## Not included

Login and encryption at rest, recurring reminders, calendar scheduling, cloud sync, and automatic backup and restore are not implemented. A design note for backup and restore is in [`docs/BACKUP_RESTORE_DESIGN.md`](docs/BACKUP_RESTORE_DESIGN.md).

The local backend has no login or network exposure; it binds to `127.0.0.1`. Keep a copy of `backend/pawfect.db` and the `backend/photos` folder if you need a backup.
