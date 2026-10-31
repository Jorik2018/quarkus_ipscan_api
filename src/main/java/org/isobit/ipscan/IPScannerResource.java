package org.isobit.ipscan;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;

@Path("")
@Produces(MediaType.APPLICATION_JSON)
public class IPScannerResource {

    private static final String SCANNER_PATH = "D:\\Advanced IP Scanner\\advanced_ip_scanner_console.exe";

    private static final long SCAN_TIMEOUT_SECONDS = 120;


    /*
     * ============================================================
     * HEALTH
     * ============================================================
     */

    @GET
    @Path("/health")
    public Response health() {

        File scanner = new File(SCANNER_PATH);

        return Response.ok(
                Map.of(
                        "status", "UP",
                        "service", "quarkus-ipscan-api",
                        "scannerAvailable", scanner.exists()
                )
        ).build();
    }


    /*
     * ============================================================
     * SCAN
     * ============================================================
     */

    @POST
    @Path("/scan")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    public Response scan(ScanRequest request) {

        if (request == null ||
                request.range() == null ||
                request.range().isBlank()) {

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "range is required"
                    ))
                    .build();
        }

        String range = request.range()
                .trim()
                .replaceAll("\\s+", "");


        /*
         * Aceptamos:
         *
         * 172.16.1.244
         * 172.16.1.1-172.16.1.254
         *
         * También dejamos "/" porque el scanner podría admitir
         * formatos de red compatibles.
         */

        if (!range.matches("[0-9.\\-/]+")) {

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "Invalid IP range"
                    ))
                    .build();
        }


        File scanner = new File(SCANNER_PATH);

        if (!scanner.exists()) {

            return Response.status(
                            Response.Status.INTERNAL_SERVER_ERROR
                    )
                    .entity(Map.of(
                            "error",
                            "Advanced IP Scanner executable not found",
                            "path",
                            SCANNER_PATH
                    ))
                    .build();
        }


        /*
         * /r = IP o rango
         * /v = incluir descubrimiento de servicios
         */

        ProcessBuilder processBuilder = new ProcessBuilder(
                scanner.getAbsolutePath(),
                "/r:" + range,
                "/v"
        );

        processBuilder.redirectErrorStream(true);


        /*
         * hostsByIp conserva el orden en el que aparecen los hosts.
         *
         * Ejemplo:
         *
         * 172.16.1.244 -> {
         *     status,
         *     name,
         *     ip,
         *     ...
         *     services: []
         * }
         */

        Map<String, Map<String, Object>> hostsByIp =
                new LinkedHashMap<>();


        /*
         * Advanced IP Scanner normalmente escribe primero hosts y
         * posteriormente servicios.
         *
         * De todas formas guardamos servicios pendientes por si
         * alguna versión devuelve un service antes que su host.
         */

        Map<String, List<Map<String, String>>> pendingServices =
                new LinkedHashMap<>();


        try {

            Process process = processBuilder.start();

            try (
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            process.getInputStream()
                                    )
                            )
            ) {

                /*
                 * Primera línea = encabezado.
                 */

                String header = reader.readLine();

                if (header == null) {

                    process.destroyForcibly();

                    return Response.status(
                                    Response.Status.INTERNAL_SERVER_ERROR
                            )
                            .entity(Map.of(
                                    "error",
                                    "Scanner returned no output"
                            ))
                            .build();
                }


                String line;

                while ((line = reader.readLine()) != null) {

                    if (line.isBlank()) {
                        continue;
                    }

                    parseAndAddLine(
                            line,
                            hostsByIp,
                            pendingServices
                    );
                }
            }


            /*
             * Esperar que termine el proceso.
             */

            boolean finished = process.waitFor(
                    SCAN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!finished) {

                process.destroyForcibly();

                return Response.status(
                                Response.Status.GATEWAY_TIMEOUT
                        )
                        .entity(Map.of(
                                "error",
                                "Scanner timeout"
                        ))
                        .build();
            }


            int exitCode = process.exitValue();

            if (exitCode != 0) {

                return Response.status(
                                Response.Status.INTERNAL_SERVER_ERROR
                        )
                        .entity(Map.of(
                                "error",
                                "Scanner failed",
                                "exitCode",
                                exitCode
                        ))
                        .build();
            }


            /*
             * Convertimos el mapa en array JSON.
             */

            List<Map<String, Object>> results =
                    new ArrayList<>(hostsByIp.values());


            return Response.ok(results).build();

        }
        catch (Exception e) {

            e.printStackTrace();

            return Response.status(
                            Response.Status.INTERNAL_SERVER_ERROR
                    )
                    .entity(Map.of(
                            "error",
                            "Error executing scanner",
                            "message",
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : "Unknown error"
                    ))
                    .build();
        }
    }


    /*
     * ============================================================
     * PARSE AND GROUP
     * ============================================================
     */

    private void parseAndAddLine(
            String line,
            Map<String, Map<String, Object>> hostsByIp,
            Map<String, List<Map<String, String>>> pendingServices
    ) {

        /*
         * -1 conserva columnas vacías.
         */

        String[] values = line.split("\\|", -1);

        if (values.length == 0) {
            return;
        }

        String status = values[0].trim();


        /*
         * ========================================================
         * SERVICE
         *
         * service | IP | type | description
         *
         * Ejemplo:
         *
         * service | 172.16.1.244 |
         * HTTP |
         * IIS Windows (Microsoft IIS httpd 10.0)
         * ========================================================
         */

        if ("service".equalsIgnoreCase(status)) {

            if (values.length < 4) {
                return;
            }

            String ip = values[1].trim();

            if (ip.isEmpty()) {
                return;
            }

            String serviceType = values[2].trim();

            if (serviceType.isEmpty() ||
                    "?".equals(serviceType)) {

                serviceType = "unknown";
            }


            Map<String, String> service =
                    new LinkedHashMap<>();

            service.put(
                    "service",
                    serviceType
            );

            service.put(
                    "description",
                    values[3].trim()
            );


            /*
             * Si el host ya existe, agregamos directamente
             * el servicio.
             */

            Map<String, Object> host =
                    hostsByIp.get(ip);

            if (host != null) {

                getServices(host).add(service);

            }
            else {

                /*
                 * Si todavía no apareció el host,
                 * guardamos temporalmente el servicio.
                 */

                pendingServices
                        .computeIfAbsent(
                                ip,
                                key -> new ArrayList<>()
                        )
                        .add(service);
            }

            return;
        }


        /*
         * ========================================================
         * HOST
         * ========================================================
         */

        if (values.length < 7) {
            return;
        }


        String ip = values[2].trim();

        if (ip.isEmpty()) {
            return;
        }


        Map<String, Object> host =
                new LinkedHashMap<>();


        host.put(
                "status",
                status
        );

        host.put(
                "name",
                emptyToNull(values[1])
        );

        host.put(
                "ip",
                ip
        );

        host.put(
                "netBIOSName",
                emptyToNull(values[3])
        );

        host.put(
                "netBIOSGroup",
                emptyToNull(values[4])
        );

        host.put(
                "manufacturer",
                emptyToNull(values[5])
        );


        String mac = values[6].trim();

        host.put(
                "macAddress",
                mac.isEmpty() ||
                        "00:00:00:00:00:00".equals(mac)
                        ? null
                        : mac
        );


        /*
         * Todos los hosts tienen services,
         * aunque esté vacío.
         */

        List<Map<String, String>> services =
                new ArrayList<>();

        host.put(
                "services",
                services
        );


        /*
         * Si anteriormente aparecieron servicios para esta IP,
         * los agregamos ahora.
         */

        List<Map<String, String>> pending =
                pendingServices.remove(ip);

        if (pending != null) {
            services.addAll(pending);
        }


        hostsByIp.put(
                ip,
                host
        );
    }


    /*
     * ============================================================
     * SERVICES HELPER
     * ============================================================
     */

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getServices(
            Map<String, Object> host
    ) {

        return (List<Map<String, String>>)
                host.get("services");
    }


    /*
     * ============================================================
     * EMPTY -> NULL
     * ============================================================
     */

    private String emptyToNull(String value) {

        if (value == null) {
            return null;
        }

        String result = value.trim();

        return result.isEmpty()
                ? null
                : result;
    }


    /*
     * ============================================================
     * REQUEST DTO
     * ============================================================
     */

    public record ScanRequest(
            String range
    ) {
    }
}