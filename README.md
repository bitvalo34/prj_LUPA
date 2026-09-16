# prj_LUPA

LUPA (Lectura Ultraresolutiva Progresiva y Adaptativa) — servidor asíncrono académico en Java 21.

## E19: base HTTP

La rama `feature/e19-http` contiene la base HTTP/1.1 acotada implementada con Java NIO.2.

Requisitos de desarrollo:

- JDK 21
- Maven 3.9.x

Compilar y probar:

```bash
mvn clean verify
```

Generar paquete ejecutable:

```bash
mvn package
java -jar target/lupa.jar --host=127.0.0.1 --port=8080 --catalog=fixture
```

Abrir `http://127.0.0.1:8080/`.

Para usar el catálogo real de A19/I19:

```bash
java -jar target/lupa.jar --catalog=file --catalog-path=data/catalog.json
```

Consulta `docs/E19.md` para configuración, pruebas, preparación offline e integración.
