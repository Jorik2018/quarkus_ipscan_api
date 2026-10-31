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
public class GreetingResource {

    private static final String SCANNER_PATH =
            "D:\\Advanced IP Scanner\\advanced_ip_scanner_console.exe";

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
         * Solo permitimos caracteres esperados para IP/rango.
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
         * /r = rango
         * /v = incluir service scan
         */

        ProcessBuilder processBuilder = new ProcessBuilder(
                scanner.getAbsolutePath(),
                "/r:" + range,
                "/v"
        );

        processBuilder.redirectErrorStream(true);

        List<Map<String, Object>> results =
                new ArrayList<>();


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
                 * Primera línea:
                 *
                 * Status | Name | Type | Operating system |
                 * IP | NetBIOS name | ...
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

                    Map<String, Object> result =
                            parseScannerLine(line);

                    if (result != null) {
                        results.add(result);
                    }
                }
            }


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
     * PARSER
     * ============================================================
     */

    private Map<String, Object> parseScannerLine(
            String line
    ) {

        /*
         * -1 mantiene columnas vacías.
         */

        String[] values = line.split("\\|", -1);

        if (values.length == 0) {
            return null;
        }


        String status = values[0].trim();

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put("status", status);


        /*
         * ========================================================
         * SERVICE
         *
         * service | IP | tipo | descripción
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
                return null;
            }

            result.put(
                    "ip",
                    values[1].trim()
            );

            String service = values[2].trim();

            /*
             * Advanced IP Scanner devuelve "?"
             * cuando no identifica el tipo.
             */

            if (service.isEmpty() ||
                    "?".equals(service)) {

                service = "unknown";
            }

            result.put(
                    "service",
                    service
            );

            result.put(
                    "description",
                    values[3].trim()
            );

            return result;
        }


        /*
         * ========================================================
         * HOST
         *
         * status
         * name
         * ip
         * netBIOSName
         * netBIOSGroup
         * manufacturer
         * macAddress
         * ========================================================
         */

        if (values.length < 7) {
            return null;
        }


        result.put(
                "name",
                values[1].trim()
        );

        result.put(
                "ip",
                values[2].trim()
        );

        result.put(
                "netBIOSName",
                values[3].trim()
        );

        result.put(
                "netBIOSGroup",
                values[4].trim()
        );

        result.put(
                "manufacturer",
                values[5].trim()
        );

        String mac = values[6].trim();

        /*
         * 00:00:00:00:00:00 realmente significa
         * que no conocemos la MAC.
         */

        result.put(
                "macAddress",
                "00:00:00:00:00:00".equals(mac)
                        ? null
                        : mac
        );


        return result;
    }


    /*
     * ============================================================
     * REQUEST
     * ============================================================
     */

    public record ScanRequest(
            String range
    ) {
    }
}