# Java Observation API

A Java-based backend server for managing observation data through a secure REST API. The project supports user authentication, persistent data storage, observation management, and integration with external services for data enrichment.

## Features

- **User Authentication:** User registration and authenticated access to protected endpoints.
- **Observation Management:** Create, retrieve, and update observation records.
- **Observatory Information:** Associate observatory details and weather information with observations.
- **Collection Management:** Manage observation collections through dedicated API endpoints.
- **GraphQL Integration:** Complete partial observation records using data from an external GraphQL service.
- **Data Persistence:** Store observation data using a configurable database path.
- **HTTPS Communication:** Expose API endpoints over HTTPS.

## Technologies

- Java
- Maven
- REST API
- HTTPS
- GraphQL
- Database persistence
- External weather services

## Getting Started

### Prerequisites

- Java Development Kit (JDK)
- Apache Maven
- Java keystore for HTTPS configuration

### Build

Clone the repository and navigate to the project directory.

```bash
mvn clean package
```

### Run

Start the server using:

```bash
java -jar target/server-1.0-jar-with-dependencies.jar <keystore-path> <keystore-password>
```

The API will be available at:

`https://localhost:8001`

## API Endpoints

| Endpoint | Method / Purpose |
|---|---|
| `/registration` | Register a new user |
| `/datarecord` | Manage observation records |
| `/datarecord/partial` | Process partial observation data |
| `/collections` | Access observation collections |

All endpoints except `/registration` require authentication.

## Configuration

The application supports the following environment variables:

| Variable | Description | Default |
|---|---|---|
| `DATABASE_PATH` | Database storage location | `database.db` |
| `GRAPHQL_URL` | External GraphQL endpoint | `http://localhost:4003/graphql` |
| `WEATHER_URL` | External weather service endpoint | `http://localhost:4001/wfs` |

## Project Overview

This project explores backend software development using Java, with an emphasis on designing RESTful services, managing persistent data, implementing authentication, and integrating external APIs.

It demonstrates the implementation of a server-side application that processes client requests, maintains observation records, and enriches stored information through external data sources.
