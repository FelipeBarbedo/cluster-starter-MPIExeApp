package com.lups.cluster_starter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@Service
public class AppStarterService {

    //private final String executorUrl = "http://localhost:8080/api/execute/publish";
    //private final String executorUrl = "http://172.24.4.101:8080/api/execute/publish";
    //private final String storagePath = "/home/javadev/cloud";
    private final String storagePath = "/home/ubuntu/cloud";
    private final RestTemplate restTemplate = new RestTemplate();

    private final BlockingQueue<Integer> requestIdPool = new ArrayBlockingQueue<>(99);


    public AppStarterService() throws InterruptedException {
        for (int i = 101; i <= 199; i++) {
            requestIdPool.add(i);
        }
    }

    public String publishCodeFilesToClusterExecutor(List<MultipartFile> files) {
        String executorUrl = "http://172.24.4.";
        String UniqueID = UUID.randomUUID().toString();
        String accessToken = this.identityAuthentication();
        int numberVMs = 0;
        String vmNumberKey = "#number_of_virtual_machines:";
        int requests = 0;

        for (MultipartFile file : files) {
            try {
                if (Objects.equals(file.getOriginalFilename(), "Makefile")) {
                    StringBuilder output = new StringBuilder();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()));

                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                        output.append("\n");
                    }
                    int aux = output.indexOf(vmNumberKey);
                    numberVMs = Character.getNumericValue(output.toString().charAt(aux + vmNumberKey.length()));
                }
            } catch (Exception e) {
                System.out.println("Error receiving code files.");
            }
        }

        if (numberVMs == 0) {
            return "Number of vms can not be 0.";
        }

        try {
            executeCommand("mkdir " + storagePath + "/" + UniqueID);
        } catch (Exception e) {
            System.out.println("Error executing mkdir command." + e.getMessage());
        }

        try {
            requests = this.createCluster(numberVMs, UniqueID);
        } catch (Exception e) {
            System.out.println("Something went wrong creating cluster.\n" + e.getMessage());
        }

        try {
            while (!this.getStackStatus("stack_" + UniqueID, accessToken).equals("CREATE_COMPLETE")) {
                System.out.println("Waiting...");
                Thread.sleep(10000);
            }
            Thread.sleep(30000);
        } catch (Exception e) {
            System.out.println("Stack status check error.");
        }

        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            //List<MultipartFile body;

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            for (MultipartFile file : files) {
                body.add("file", file.getResource());
            }
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<String> responseEntity = null;
            try {
                if (requests != 0) {
                    executorUrl = executorUrl.concat(requests + ":8080/api/execute/publish");
                    responseEntity = restTemplate.exchange(executorUrl, HttpMethod.POST, requestEntity, String.class);
                } else {
                    throw new IllegalStateException();
                }
            } catch (Exception e) {
                System.out.println("Error sending POST request." + e.getMessage());
            }

            // System.out.println(responseEntity.getStatusCode());
            // System.out.println(responseEntity.getBody());

            try (FileWriter writer = new FileWriter(storagePath + "/" + UniqueID + "/" + "result.txt")) {
                assert responseEntity != null;
                if (responseEntity.getBody() != null)
                    writer.write(responseEntity.getBody());
                else
                    writer.write("Something went wrong.");

                System.out.println("1 - Code files have been successfully saved to " + storagePath);
            } catch (IOException e) {
                System.out.println("1 - An error occurred while saving the Code Files.");
            }

        } catch (Exception e) {
            System.out.println("Error trying to check if the cluster is up.");
        }

        try {
            String token = this.identityAuthentication();

            String href = getRequestForDeletion("stack_" + UniqueID, token);

            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Auth-Token", token);

            HttpEntity<String> requestEntity = new HttpEntity<>(headers);

            ResponseEntity<String> responseEntity = restTemplate.exchange(href, HttpMethod.DELETE, requestEntity, String.class);

            System.out.println("Result received");
            if (requests != 0)
                requestIdPool.put(requests);

        } catch (Exception e) {
            System.out.println("Error Deleting cluster.");
        }

        return UniqueID;
    }

    /*public String storeCodeFiles(String result) {

        try (FileWriter writer = new FileWriter(storagePath + "/" + generateUniqueID + "/" + "result.txt")) {
            writer.write(result);
            System.out.println("1 - Code files have been successfully saved to " + storagePath);
        } catch (IOException e) {
            System.out.println("1 - An error occurred while saving the Code Files.");
        }

        return "200";
    }*/

    public String identityAuthentication() {

        String identityURL = "http://192.168.0.101/identity/v3/auth/tokens";
        String identityRequestBody = """
                {
                    "auth": {
                        "identity": {
                            "methods": [
                                "application_credential"
                            ],
                            "application_credential": {
                                "id": "Your-credential-id",
                                "secret": "Your-password"
                            }
                        }
                    }
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> requestEntity = new HttpEntity<>(identityRequestBody, headers);

        // Perform POST request
        ResponseEntity<String> responseEntity = this.restTemplate.exchange(identityURL, HttpMethod.POST, requestEntity, String.class);

        // Get HTTP status code
        HttpStatusCode statusCode = responseEntity.getStatusCode();
        System.out.println("2 - Status Code for the identity request: " + statusCode.value());

        // Get headers
        HttpHeaders responseHeaders = responseEntity.getHeaders();

        // Make the POST request
        return responseHeaders.getFirst("X-Subject-Token");
    }

    public String getRequestForDeletion(String codeKeyID, String accessToken) {

        StringBuilder href = new StringBuilder();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();

        headers.set("X-Auth-Token", accessToken);

        HttpEntity<String> request = new HttpEntity<>(headers);

        String url = "http://192.168.0.101/heat-api/v1/6808d22dacd3479ca717f96bea26a694/stacks/" + codeKeyID;

        String response = restTemplate.exchange(url, HttpMethod.GET, request, String.class).getBody();

        ObjectMapper object = new ObjectMapper();

        try {

            JsonNode jsonNode = object.readTree(response);

            href.append("http://192.168.0.101/heat-api/v1/");
            href.append(jsonNode.get("stack").get("parameters").get("OS::project_id").textValue());
            href.append("/stacks/");
            href.append(jsonNode.get("stack").get("stack_name").textValue());
            href.append("/");
            href.append(jsonNode.get("stack").get("id").textValue());
        } catch (Exception e) {
            System.out.println("Error processing JSON for stack deletion (getRequestForDeletion).");
        }

        return href.toString();
    }

    public int createCluster(int numberOfVMs, String UniqueID) throws InterruptedException {
        StringBuilder heatRequestBody = new StringBuilder();
        String accessToken = identityAuthentication();
        int uniqueNumber = requestIdPool.take();

        String heatURL = "http://192.168.0.101/heat-api/v1/6808d22dacd3479ca717f96bea26a694/stacks";

        String parameters = """
                {
                "parameters": {
                    "image": "generic_snap",
                    "flavor": "ds1G",
                    "network": "heat-net"
                },
                """;
        heatRequestBody.append(parameters);

        String template = """           
                "stack_name": "${stack_name}",
                "template": {
                    "heat_template_version": "2018-08-31",
                    "description": "A simple template to create n VMs on OpenStack.",
                    "parameters": {
                        "image": {
                            "type": "string",
                            "description": "Name or ID of the image to use for the VMs",
                            "default": "cirros-0.6.2-x86_64-disk"
                        },
                        "flavor": {
                            "type": "string",
                            "description": "Flavor to use for the VMs",
                            "default": "cirros256"
                        },
                        "network": {
                            "type": "string",
                            "description": "Network to attach to the VMs",
                            "default": "heat-net"
                        }
                    },
                    "resources": {
                """;
        heatRequestBody.append(template.replace("${stack_name}", "stack_" + UniqueID));

        String clusterNetwork = """
                "${cluster_net}": {
                    "type": "OS::Neutron::Net",
                    "properties": {
                        "name": "${cluster_net}"
                    }
                },
                """;
        heatRequestBody.append(clusterNetwork.replace("${cluster_net}", "net_" + UniqueID));

        String clusterSubnet = """
                "${cluster_subnet}": {
                    "type": "OS::Neutron::Subnet",
                    "properties": {
                        "network_id": { "get_resource": "${cluster_net}" },
                        "cidr": "${cluster_net_cidr}",
                        "ip_version": 4
                    }
                },
                """;
        String auxClusterSubnet = clusterSubnet.replace("${cluster_subnet}", "subnet_" + uniqueNumber);
        auxClusterSubnet = auxClusterSubnet.replace("${cluster_net}", "net_" + UniqueID);
        auxClusterSubnet = auxClusterSubnet.replace("${cluster_net_cidr}", "10.0." + uniqueNumber + ".0/24");
        heatRequestBody.append(auxClusterSubnet);

        String clusterRouter = """
                "router_interface": {
                    "type": "OS::Neutron::RouterInterface",
                    "properties": {
                        "router_id": "router1",
                        "subnet_id": { "get_resource": "${cluster_subnet}" }
                    }
                },
                """;
        heatRequestBody.append(clusterRouter.replace("${cluster_subnet}", "subnet_" + uniqueNumber));

        String clusterPort = """
                "${cluster_port}": {
                    "type": "OS::Neutron::Port",
                    "properties": {
                        "network_id": { "get_resource": "${cluster_net}" },
                        "fixed_ips": [{
                            "subnet_id": { "get_resource": "${cluster_subnet}" }
                        }]
                    }
                },
                """;
        String auxClusterPort = clusterPort.replace("${cluster_port}", "cluster_port_" + UniqueID);
        auxClusterPort = auxClusterPort.replace("${cluster_net}", "net_" + UniqueID);
        auxClusterPort = auxClusterPort.replace("${cluster_subnet}", "subnet_" + uniqueNumber);
        heatRequestBody.append(auxClusterPort);

        String managerFloatingIP = """
                "manager_floating_ip": {
                    "type": "OS::Neutron::FloatingIP",
                    "properties": {
                        "floating_network": "public",
                        "floating_ip_address": "${manager_floating_ip}"
                    }
                },
                """;
        heatRequestBody.append(managerFloatingIP.replace("${manager_floating_ip}", "172.24.4." + uniqueNumber));

        String managerPort = """
                "manager_port": {
                    "type": "OS::Neutron::Port",
                    "properties": {
                        "network_id": { "get_resource": "${cluster_net}" },
                        "fixed_ips": [
                        {
                            "subnet_id": { "get_resource": "${cluster_subnet}" },
                            "ip_address": "${manager_ip}"
                        }
                        ]
                    }
                },
                "manager_floating_ip_association": {
                    "type": "OS::Neutron::FloatingIPAssociation",
                    "properties": {
                        "floatingip_id": { "get_resource": "manager_floating_ip" },
                        "port_id": { "get_resource": "manager_port" }
                    }
                },
                """;
        String auxManagerPort = managerPort.replace("${cluster_net}", "net_" + UniqueID);
        auxManagerPort = auxManagerPort.replace("${cluster_subnet}", "subnet_" + uniqueNumber);
        auxManagerPort = auxManagerPort.replace("${manager_ip}", "10.0." + uniqueNumber + ".101");
        heatRequestBody.append(auxManagerPort);

        String workerPort = """            
                "${worker_port}": {
                    "type": "OS::Neutron::Port",
                    "properties": {
                        "network_id": { "get_resource": "${cluster_net}" },
                        "fixed_ips": [
                        {
                            "subnet_id": { "get_resource": "${cluster_subnet}" },
                            "ip_address": "${worker_ip}"
                        }
                        ]
                    }
                },
                """;
        for (int i = 0; i < (numberOfVMs - 1); i++) {
            String auxWorkerPort = workerPort;
            String nameWorker = "worker_port".concat(Integer.toString(i));
            String ipWorker = "10.0." + uniqueNumber + ".10".concat(Integer.toString(i + 2));

            auxWorkerPort = auxWorkerPort.replace("${worker_port}", nameWorker);
            auxWorkerPort = auxWorkerPort.replace("${worker_ip}", ipWorker);
            auxWorkerPort = auxWorkerPort.replace("${cluster_net}", "net_" + UniqueID);
            auxWorkerPort = auxWorkerPort.replace("${cluster_subnet}", "subnet_" + uniqueNumber);
            auxWorkerPort = auxWorkerPort.replace("${manager_ip}", "10.0." + uniqueNumber + ".101");
            heatRequestBody.append(auxWorkerPort);
        }

        String managerVM = """
                "manager": {
                    "type": "OS::Nova::Server",
                    "properties": {
                        "name": "manager",
                        "image": "generic_snap",
                        "flavor": {
                            "get_param": "flavor"
                        },
                        "networks": [
                        {
                            "port": { "get_resource": "manager_port" }
                        }
                        ],
                        "user_data": "#!/bin/bash\\necho '/home/ubuntu/cloud *(rw,sync,no_root_squash,no_subtree_check)' | sudo tee -a /etc/exports > /dev/null\\nsudo exportfs -a\\nsudo service nfs-kernel-server restart\\nsudo -u ubuntu nohup java -jar /home/ubuntu/cluster-executor-v2.0.jar > /home/ubuntu/log 2>&1 &"
                    }
                },
                """;
        heatRequestBody.append(managerVM);

        // #!/bin/bash\nsudo bash -c 'echo /home/ubuntu/cloud *(rw,sync,no_root_squash,no_subtree_check) >> /etc/exports'\nsudo exportfs -a\nsudo service nfs-kernel-server restart\nnohup java -jar /home/ubuntu/cluster-subscriber-v1.1-to-ip-107.jar > /dev/null 2>&1 &

        String workerVM = """
                "${name_of_the_virtual_machine}": {
                    "type": "OS::Nova::Server",
                    "depends_on": ["manager"],
                    "properties": {
                        "name": "${name_of_the_virtual_machine}",
                        "image": "generic_snap",
                        "flavor": {
                            "get_param": "flavor"
                        },
                        "networks": [
                        {
                            "port": { "get_resource": "${worker_port}" }
                        }
                        ],
                        "user_data": "#!/bin/bash\\nsudo mount -t nfs manager:/home/ubuntu/cloud /home/ubuntu/cloud"
                    }
                }
                """;

        // #!/bin/bash\nsudo mount -t nfs manager:/home/ubuntu/cloud /home/ubuntu/cloud

        String comma = ",";

        String endTemplate = """
                }
                },
                "timeout_mins": 60
                }
                """;

        for (int i = 0; i < (numberOfVMs - 1); i++) {
            String auxWorkerVM = workerVM;
            String nameWorker = "worker".concat(Integer.toString(i));
            String nameWorkerPort = "worker_port".concat(Integer.toString(i));

            auxWorkerVM = auxWorkerVM.replace("${name_of_the_virtual_machine}", nameWorker);
            auxWorkerVM = auxWorkerVM.replace("${worker_port}", nameWorkerPort);
            if (i == 0) {
                auxWorkerVM = auxWorkerVM.replace("${worker_host}", "manager");
            } else {
                nameWorker = "worker".concat(Integer.toString(i - 1));
                auxWorkerVM = auxWorkerVM.replace("${worker_host}", nameWorker);
            }
            heatRequestBody.append(auxWorkerVM);

            if (i < numberOfVMs - 2)
                heatRequestBody.append(comma);
        }

        heatRequestBody.append(endTemplate);

        String code = this.createVirtualMachines(heatURL, heatRequestBody.toString(), accessToken);
        return uniqueNumber;
    }

    public String createVirtualMachines(String url, String requestBody, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Auth-Token", accessToken);
        headers.set("Content-Type", "application/json");

        HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

        // Perform POST request using exchange()
        ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);

        // Get response body (JSON or other format)
        // String responseBody = responseEntity.getBody();
        // System.out.println("Response Body: " + responseBody);

        // Get HTTP status code
        HttpStatusCode statusCode = responseEntity.getStatusCode();
        System.out.println("Status Code: " + statusCode.value());

        // Get headers
        HttpHeaders responseHeaders = responseEntity.getHeaders();
        System.out.println("Response Headers: " + responseHeaders);

        return statusCode.toString();
    }

    private void executeCommand(String command) throws IOException, InterruptedException {

        Process process = Runtime.getRuntime().exec(command);
        process.waitFor();
    }

    public String getStackStatus(String codeKeyID, String accessToken) throws JsonProcessingException {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();

        headers.set("X-Auth-Token", accessToken);

        HttpEntity<String> request = new HttpEntity<>(headers);

        String url = "http://192.168.0.101/heat-api/v1/6808d22dacd3479ca717f96bea26a694/stacks/" + codeKeyID;

        String response = restTemplate.exchange(url, HttpMethod.GET, request, String.class).getBody();

        ObjectMapper object = new ObjectMapper();

        JsonNode jsonNode = object.readTree(response);

        return jsonNode.get("stack").get("stack_status").textValue();
    }

    public ResponseEntity<Resource> retrieveResult(String resultDirId) {
        File file = new File(storagePath + "/" + resultDirId + "/result.txt");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file.getName()); // Dynamic filename
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE); // Correct content type

        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .body(resource);
    }
}
