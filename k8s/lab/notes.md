1- Creating a workspace called bartu-bartu-test, not creating a session

kubectl -n henkan-designer get deployment
kubectl -n henkan-designer get appdefinition
kubectl -n henkan-designer get workspace
kubectl -n henkan-designer get session
kubectl -n henkan-designer get pods
kubectl -n henkan-designer get svc
kubectl -n henkan-designer get ing
kubectl -n henkan-designer get pvc

## Deployment
NAME                                         READY   UP-TO-DATE   AVAILABLE   AGE
conversion-webhook                           1/1     1            1           4d13h
operator-deployment                          1/1     1            1           4d13h

## Appdefinition
NAME              AGE
henkan-designer   4d14h


## Workspace
NAME                      AGE
bartu-bartu-test          58m
gbm-belbim-etl            4d14h
gbm-emre-dev              3d13h
gbm-henkan-lineage-test   4d14h
sarp-sarp-dev             17h

## Session
No resources found in henkan-designer namespace.

## Pod
NAME                                   READY   STATUS    RESTARTS        AGE
conversion-webhook-648675f45f-nnxdb    1/1     Running   0               4d13h
image-preloading-4lb5k                 1/1     Running   0               4d13h
image-preloading-6n5jc                 1/1     Running   0               4d13h
image-preloading-wkmgm                 1/1     Running   0               4d13h
image-preloading-zqq7w                 1/1     Running   0               4d1
operator-deployment-759cc888b7-v2v2n   1/1     Running   17 (152m ago)   4d13h

## SVC
NAME                         TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)   AGE
conversion-webhook-service   ClusterIP   10.43.27.118   <none>        443/TCP   4d13h

## Ing
NAME                          CLASS   HOSTS                           ADDRESS                                           PORTS     AGE
theia-cloud-demo-ws-ingress   nginx   designer.172.12.2.32.sslip.io   172.12.2.31,172.12.2.32,172.12.2.35,172.12.2.39   80, 443   4d13h

## PVC
NAME                                    STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   VOLUMEATTRIBUTESCLASS   AGE
ws-bartu-henkan-designer-e970b4e57ee4   Bound    pvc-46b66e87-2d47-42b2-ab2b-3ce22b948256   250Mi      RWO            local-path     <unset>                 2m53s
ws-gbm-henkan-designer-030acede1852     Bound    pvc-b3a4154d-3937-4d2e-bb93-e74fd331f48d   250Mi      RWO            local-path     <unset>                 3d12h
ws-gbm-henkan-designer-587f3c15fd03     Bound    pvc-253d9ae0-3452-46f8-8ce0-7931f9c0be84   250Mi      RWO            local-path     <unset>                 4d13h
ws-gbm-henkan-designer-a2bc974fc932     Bound    pvc-c2682808-8161-4a99-9f17-be1a8479cc5f   250Mi      RWO            local-path     <unset>                 4d13h
ws-sarp-henkan-designer-7732812275e3    Bound    pvc-127b499e-19b7-4f57-99b1-640f14226425   250Mi      RWO            local-path     <unset>                 16h

---------------------------------------------------------------------------------------
2- After Creating a Session in that workspace

kubectl -n henkan-designer get deployment
kubectl -n henkan-designer get session
kubectl -n henkan-designer get pods
kubectl -n henkan-designer get svc
kubectl -n henkan-designer get ing
kubectl -n henkan-designer get pvc

## Deployment
NAME                                         READY   UP-TO-DATE   AVAILABLE   AGE
conversion-webhook                           1/1     1            1           4d13h
operator-deployment                          1/1     1            1           4d13h
session-bartu-henkan-designer-8c29c58e895b   1/1     1            1           30m *******************

## Appdefinition
NAME              AGE
henkan-designer   4d14h


## Workspace
NAME                      AGE
bartu-bartu-test          58m
gbm-belbim-etl            4d14h
gbm-emre-dev              3d13h
gbm-henkan-lineage-test   4d14h
sarp-sarp-dev             17h

## Session
NAME               AGE
bartu-bartu-test   20s

## Pod
NAME                                                          READY   STATUS    RESTARTS        AGE
conversion-webhook-648675f45f-nnxdb                           1/1     Running   0               4d13h
image-preloading-4lb5k                                        1/1     Running   0               4d13h
image-preloading-6n5jc                                        1/1     Running   0               4d13h
image-preloading-wkmgm                                        1/1     Running   0               4d13h
image-preloading-zqq7w                                        1/1     Running   0               4d13h
operator-deployment-759cc888b7-v2v2n                          1/1     Running   17 (154m ago)   4d13h
session-bartu-henkan-designer-8c29c58e895b-55776f75dd-wh7s8   2/2     Running   0               19s  **********************

## SVC
NAME                                         TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)    AGE
conversion-webhook-service                   ClusterIP   10.43.27.118   <none>        443/TCP    4d13h
session-bartu-henkan-designer-8c29c58e895b   ClusterIP   10.43.12.122   <none>        3000/TCP   20s ************************

## Ing
NAME                          CLASS   HOSTS                                                         ADDRESS                                           PORTS     AGE
theia-cloud-demo-ws-ingress   nginx   designer.172.12.2.32.sslip.io,designer.172.12.2.32.sslip.io   172.12.2.31,172.12.2.32,172.12.2.35,172.12.2.39   80, 443   4d13h

## PVC
NAME                                    STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   VOLUMEATTRIBUTESCLASS   AGE
ws-bartu-henkan-designer-e970b4e57ee4   Bound    pvc-46b66e87-2d47-42b2-ab2b-3ce22b948256   250Mi      RWO            local-path     <unset>                 4m4s
ws-gbm-henkan-designer-030acede1852     Bound    pvc-b3a4154d-3937-4d2e-bb93-e74fd331f48d   250Mi      RWO            local-path     <unset>                 3d12h
ws-gbm-henkan-designer-587f3c15fd03     Bound    pvc-253d9ae0-3452-46f8-8ce0-7931f9c0be84   250Mi      RWO            local-path     <unset>                 4d13h
ws-gbm-henkan-designer-a2bc974fc932     Bound    pvc-c2682808-8161-4a99-9f17-be1a8479cc5f   250Mi      RWO            local-path     <unset>                 4d13h
ws-sarp-henkan-designer-7732812275e3    Bound    pvc-127b499e-19b7-4f57-99b1-640f14226425   250Mi      RWO            local-path     <unset>                 16h


### kubectl -n henkan-designer get ingress
theia-cloud-demo-ws-ingress   nginx   designer.172.12.2.32.sslip.io,designer.172.12.2.32.sslip.io   172.12.2.31,172.12.2.32,172.12.2.35,172.12.2.39   80, 443   4d12h


# Theia-cloud operator deployment (shows --keycloak*, --oAuth2ProxyVersion, etc.)
kubectl -n henkan-designer get deploy operator-deployment -o yaml

# OAuth2 proxy config and templates used by sessions
kubectl -n henkan-designer get configmap oauth2-proxy-config -o yaml
kubectl -n henkan-designer get configmap oauth2-templates    -o yaml

# Keycloak ingress (URL used in configs)
kubectl -n henkan-keycloak get ing henkan-keycloak -o yaml










