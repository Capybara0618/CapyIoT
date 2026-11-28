"""Bootstrap a namespace-scoped release identity, then stop using the Kind admin credential."""

import base64
import json
from pathlib import Path

import cloud_native_common as cn
from microservices_common import write_json


def install_deployer(target):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    user = "capyiot-cn-deployer"
    role = "capyiot-cn-release"
    namespace = {"apiVersion":"v1","kind":"Namespace","metadata":{"name":cn.NAMESPACE,"labels":{"io.capyiot.cn/target":cn.CLUSTER}}}
    objects = [namespace,
        {"apiVersion":"rbac.authorization.k8s.io/v1","kind":"Role","metadata":{"name":role,"namespace":cn.NAMESPACE},"rules":[
            {"apiGroups":[""],"resources":["pods","pods/log","services","configmaps","secrets","serviceaccounts","events"],"verbs":["get","list","watch","create","patch","update","delete"]},
            {"apiGroups":[""],"resources":["pods/exec"],"verbs":["create","get"]},
            {"apiGroups":["apps"],"resources":["deployments","deployments/scale","replicasets"],"verbs":["get","list","watch","create","patch","update","delete"]},
            {"apiGroups":["batch"],"resources":["jobs"],"verbs":["get","list","watch","create","patch","update","delete"]},
            {"apiGroups":["discovery.k8s.io"],"resources":["endpointslices"],"verbs":["get","list","watch","create","patch","update","delete"]}]},
        {"apiVersion":"rbac.authorization.k8s.io/v1","kind":"RoleBinding","metadata":{"name":role,"namespace":cn.NAMESPACE},
         "roleRef":{"apiGroup":"rbac.authorization.k8s.io","kind":"Role","name":role},"subjects":[{"kind":"User","name":user,"apiGroup":"rbac.authorization.k8s.io"}]},
        {"apiVersion":"rbac.authorization.k8s.io/v1","kind":"ClusterRole","metadata":{"name":role},"rules":[
            {"apiGroups":[""],"resources":["namespaces"],"resourceNames":["kube-system",cn.NAMESPACE],"verbs":["get"]},
            {"apiGroups":[""],"resources":["namespaces"],"resourceNames":[cn.NAMESPACE],"verbs":["patch","update"]}]},
        {"apiVersion":"rbac.authorization.k8s.io/v1","kind":"ClusterRoleBinding","metadata":{"name":role},
         "roleRef":{"apiGroup":"rbac.authorization.k8s.io","kind":"ClusterRole","name":role},"subjects":[{"kind":"User","name":user,"apiGroup":"rbac.authorization.k8s.io"}]}]
    cn.execute(target.kubectl("apply","-f","-"),data=json.dumps({"apiVersion":"v1","kind":"List","items":objects}).encode())
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    csr=x509.CertificateSigningRequestBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,user)])).sign(key,hashes.SHA256())
    name="capyiot-cn-deployer-"+target.manifest["ownerId"][:12]
    request={"apiVersion":"certificates.k8s.io/v1","kind":"CertificateSigningRequest","metadata":{"name":name},
             "spec":{"request":base64.b64encode(csr.public_bytes(serialization.Encoding.PEM)).decode(),
                     "signerName":"kubernetes.io/kube-apiserver-client","expirationSeconds":86400,"usages":["client auth"]}}
    cn.execute(target.kubectl("create","-f","-"),data=json.dumps(request).encode())
    cn.execute(target.kubectl("certificate","approve",name))
    cn.execute(target.kubectl("wait","--for=jsonpath={.status.certificate}","csr/"+name,"--timeout=60s"))
    cert=json.loads(cn.execute(target.kubectl("get","csr",name,"-o","json")))["status"]["certificate"]
    parsed=x509.load_pem_x509_certificate(base64.b64decode(cert))
    if parsed.subject.get_attributes_for_oid(NameOID.COMMON_NAME)[0].value != user or parsed.public_key().public_numbers() != key.public_key().public_numbers():
        raise ValueError("Cluster signer returned a mismatched deployment certificate")
    current=json.loads(cn.execute(target.kubectl("config","view","--minify","--raw","-o","json")))
    original=target.bundle/"bootstrap-kubeconfig"
    original.write_bytes((target.bundle/"kubeconfig").read_bytes());original.chmod(0o600)
    limited={"apiVersion":"v1","kind":"Config","clusters":current["clusters"],
             "contexts":[{"name":cn.CONTEXT,"context":{"cluster":current["clusters"][0]["name"],"user":user,"namespace":cn.NAMESPACE}}],
             "current-context":cn.CONTEXT,"users":[{"name":user,"user":{"client-certificate-data":cert,
                 "client-key-data":base64.b64encode(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())).decode()}}]}
    write_json(target.bundle/"kubeconfig",limited)
