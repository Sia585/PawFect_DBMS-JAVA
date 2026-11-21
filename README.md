# Pawfect

A local pet care manager with a Java HTTP backend and SQLite database. It stores pet profiles and care records on the computer running the server.

## Run it

Install Java 17 or newer and Maven, open a terminal in the `backend` directory, and run:

```sh
mvn compile exec:java
```

Then open <http://localhost:8080>. The SQLite database file (`pawfect.db`) is created in the `backend` directory the first time the server starts. Stop the server with Ctrl+C. To use a different port, set `PAWFECT_PORT` before starting it.

## Features

- Pet profiles with species, breed, birthday, sex, weight, vet, emoji, and notes
- Records for vaccination, medication, vet visits, feeding, grooming, weight, and other care
- Due dates grouped into overdue, soon, and upcoming reminders
- Per-pet care timelines, dashboard summaries, search, and filters
- Export a pet's profile and care history to CSV
- SQLite foreign keys and transactional saves for pet and care data

The local backend has no login or network exposure; it binds to `127.0.0.1`. Keep a copy of `backend/pawfect.db` if you need a backup.
