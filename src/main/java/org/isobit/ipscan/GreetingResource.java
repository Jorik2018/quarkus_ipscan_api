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

    private static final String SCANNER_PATH_1 =
            "C:\\Program Files (x86)\\Advanced IP Scanner\\advanced_ip_scanner_console.exe";

    private static final String SCANNER_PATH_2 =
            "D:\\Advanced IP Scanner\\advanced_ip_scanner_console.exe";

    private static final long SCAN_TIMEOUT_SECONDS = 60;


    /*
     * ============================================================
     * HEALTH CHECK
     * ============================================================
     */

    @GET
    @Path("/health")
    public Response health() {

        return Response.ok(
                Map.of(
                        "status", "UP",
                        "service", "quarkus-ipscan-api"
                )
        ).build();
    }


    /*
     * ============================================================
     * IP SCAN
     * ============================================================
     */

    @POST
    @Path("/scan")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    public Response scan(ScanRequest request) {

        /*
         * Validar request
         */

        if (request == null ||
                request.range() == null ||
                request.range().isBlank()) {

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            Map.of(
                                    "error", "range is required"
                            )
                    )
                    .build();
        }

        String range = request.range().trim();

        /*
         * Validación básica.
         *
         * Advanced IP Scanner admite rangos como:
         *
         * 172.16.1.1-172.16.1.254
         *
         * Evitamos enviar caracteres arbitrarios al ejecutable.
         */

        if (!range.matches("[0-9.\\-/]+")) {

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            Map.of(
                                    "error", "Invalid IP range"
                            )
                    )
                    .build();
        }


        /*
         * Buscar Advanced IP Scanner
         */

        File scanner = findScanner();

        if (scanner == null) {

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            Map.of(
                                    "error",
                                    "Advanced IP Scanner executable not found"
                            )
                    )
                    .build();
        }


        /*
         * Construir comando
         */

        ProcessBuilder processBuilder = new ProcessBuilder(
                scanner.getAbsolutePath(),
                "/r:" + range
        );

        /*
         * stderr se mezcla con stdout para evitar que el proceso
         * se quede bloqueado si llena el buffer de errores.
         */

        processBuilder.redirectErrorStream(true);

        List<Map<String, String>> results = new ArrayList<>();


        try {

            Process process = processBuilder.start();

            /*
             * Leer salida del scanner.
             */

            try (
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            process.getInputStream()
                                    )
                            )
            ) {

                String line = reader.readLine();

                /*
                 * Scanner sin salida
                 */

                if (line == null) {

                    process.destroyForcibly();

                    return Response.status(
                                    Response.Status.INTERNAL_SERVER_ERROR
                            )
                            .entity(
                                    Map.of(
                                            "error",
                                            "Scanner returned no output"
                                    )
                            )
                            .build();
                }


                /*
                 * Primera línea = encabezados.
                 *
                 * Actualmente no necesitamos utilizarlos,
                 * pero la consumimos antes de procesar resultados.
                 */

                while ((line = reader.readLine()) != null) {

                    if (line.isBlank()) {
                        continue;
                    }

                    Map<String, String> result =
                            parseScannerLine(line);

                    if (result != null) {
                        results.add(result);
                    }
                }
            }


            /*
             * Esperar finalización con timeout.
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
                        .entity(
                                Map.of(
                                        "error",
                                        "Scanner timeout"
                                )
                        )
                        .build();
            }


            /*
             * Comprobar código de salida.
             */

            int exitCode = process.exitValue();

            if (exitCode != 0) {

                return Response.status(
                                Response.Status.INTERNAL_SERVER_ERROR
                        )
                        .entity(
                                Map.of(
                                        "error",
                                        "Scanner failed",
                                        "exitCode",
                                        exitCode
                                )
                        )
                        .build();
            }


            /*
             * Respuesta correcta.
             */

            return Response.ok(results).build();

        }
        catch (Exception e) {

            e.printStackTrace();

            return Response.status(
                            Response.Status.INTERNAL_SERVER_ERROR
                    )
                    .entity(
                            Map.of(
                                    "error",
                                    "Error executing scanner",
                                    "message",
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : "Unknown error"
                            )
                    )
                    .build();
        }
    }


    /*
     * ============================================================
     * FIND SCANNER
     * ============================================================
     */

    private File findScanner() {

        File scanner = new File(SCANNER_PATH_1);

        if (scanner.exists()) {
            return scanner;
        }

        scanner = new File(SCANNER_PATH_2);

        if (scanner.exists()) {
            return scanner;
        }

        return null;
    }


    /*
     * ============================================================
     * PARSE ADVANCED IP SCANNER OUTPUT
     * ============================================================
     */

    private Map<String, String> parseScannerLine(String line) {

        /*
         * -1 conserva columnas vacías.
         *
         * Esto es importante porque Advanced IP Scanner
         * puede devolver campos vacíos entre pipes.
         */

        String[] values = line.split("\\|", -1);

        if (values.length == 0) {
            return null;
        }

        String status = values[0].trim();

        Map<String, String> result = new LinkedHashMap<>();

        result.put("status", status);


        /*
         * Líneas correspondientes a servicios detectados.
         */

        if ("service".equalsIgnoreCase(status)) {

            if (values.length < 4) {
                return null;
            }

            result.put("name", values[1].trim());
            result.put("type", values[2].trim());
            result.put(
                    "operatingSystem",
                    values[3].trim()
            );

            return result;
        }


        /*
         * Equipos/IPs normales.
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

        result.put(
                "macAddress",
                values[6].trim()
        );

        return result;
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
/*Status 	| Name 		| Type 			| Operating system 	| IP 			| NetBIOS name 		| NetBIOS group | Manufacturer 		| MAC address 			| User
alive 	| GRAD02 										| 172.16.1.197 	| GRAD02 			| GRAD 			| Hewlett Packard 	| 2C:44:FD:2E:E6:43    	|
alive 	| SGADF09 										| 172.16.1.242 	| SGADF09 			| SGADF 		| Inventec 			| 00:26:6C:28:B5:38 	|
alive 	| 172.16.2.47 									| 172.16.2.47 	|  					|  				|  					| 50:81:40:9D:23:6C |
alive 	| 172.16.2.58 									| 172.16.2.58 	|  					|  				|  					| 96:5A:D9:58:D7:7E |
alive 	| 172.16.2.64 									| 172.16.2.64 	|  					|  				|  					| E0:70:EA:A4:44:33 |
unknown | 172.16.2.153 									| 172.16.2.153 	|  					|  				|  					| 00:00:00:00:00:00 |
unknown | 172.16.2.154 									| 172.16.2.154 	|  					|  				|  					| 00:00:00:00:00:00 |
unknown | 172.16.2.152 									| 172.16.2.152 	|  					|  				|  					| 00:00:00:00:00:00 |
unknown | 172.16.2.155 									| 172.16.2.155 	|  					|  				|  					| 00:00:00:00:00:00 |
service | 172.16.1.244 | ? | IIS Windows (Microsoft IIS httpd 10.0)
service | 172.16.1.244 | HTTP | IIS Windows (Microsoft IIS httpd 10.0)
service | 172.16.1.244 | ? | Windows
service | 172.16.1.239 | ? | IIS Windows (Microsoft IIS httpd 10.0)
service | 172.16.1.239 | HTTP | IIS Windows (Microsoft IIS httpd 10.0)
service | 172.16.1.239 | ? | Windows
service | 172.16.1.200 | ? | IIS Windows (Microsoft IIS httpd 8.5)
service | 172.16.1.200 | HTTP | IIS Windows (Microsoft IIS httpd 8.5)
service | 172.16.1.200 | ? | Windows
*/


        //File f = new File("C:\\Program Files (x86)\\Advanced IP Scanner\\advanced_ip_scanner_console.exe");
        //if (!f.exists())
          //  f = new File("D:\\Advanced IP Scanner\\advanced_ip_scanner_console.exe");

      