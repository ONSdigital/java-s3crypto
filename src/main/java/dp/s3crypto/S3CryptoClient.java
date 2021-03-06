package dp.s3crypto;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.Permission;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class S3CryptoClient extends AmazonS3Client implements S3Crypto {

    private RSAPrivateKey privKey;
    private RSAPublicKey pubKey;
    private boolean hasUserDefinedPSK = false;
    private final String ENCRYPTION_KEY_HEADER = "Pskencrypted";
    private AmazonS3Client s3Client;
    private final String NO_PRIVATE_KEY_MESSAGE = "you have not provided a private key and therefore do not have permission to complete this action";

    public S3CryptoClient(ClientConfiguration clientConfiguration, RSAPrivateKey privKey) {
        s3Client = new AmazonS3Client();
        s3Client.builder().setClientConfiguration(clientConfiguration);
        s3Client.builder().build();

        RSAPrivateCrtKey privk = (RSAPrivateCrtKey) privKey;
        RSAPublicKeySpec publicKeySpec = new java.security.spec.RSAPublicKeySpec(privk.getModulus(),
                privk.getPublicExponent());
        KeyFactory keyFactory;
        try {
            keyFactory = KeyFactory.getInstance("RSA");
            PublicKey pubKey = keyFactory.generatePublic(publicKeySpec);
            this.pubKey = (RSAPublicKey) pubKey;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
        }

        this.privKey = privKey;
    }

    public S3CryptoClient(AmazonS3Client s3Client) {
        this.s3Client = s3Client;
        this.hasUserDefinedPSK = true;
    }

    public S3CryptoClient(ClientConfiguration clientConfiguration, RSAPublicKey pubKey) {
        s3Client = new AmazonS3Client();
        s3Client.builder().setClientConfiguration(clientConfiguration);
        s3Client.builder().build();
        this.pubKey = pubKey;
    }

    public S3CryptoClient(ClientConfiguration clientConfiguration) {
        s3Client = new AmazonS3Client();
        s3Client.builder().setClientConfiguration(clientConfiguration);
        s3Client.builder().build();
        this.hasUserDefinedPSK = true;
    }

    /**
     * Wraps the SDK method by creating an encrypted PSK which is stored as object
     * metadata and temporarily as its own file while the multipart upload is in
     * progress.
     *
     * @return InitiateMultipartUploadResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public InitiateMultipartUploadResult initiateMultipartUpload(
            InitiateMultipartUploadRequest initiateMultipartUploadRequest) throws SdkClientException {

        if (!hasUserDefinedPSK) {
            byte[] psk = createPSK();
            try {
                String encodedKey = encryptKey(psk);
                InputStream stream = new ByteArrayInputStream(encodedKey.getBytes());

                ObjectMetadata keyMetadata = initiateMultipartUploadRequest.getObjectMetadata();
                if (keyMetadata == null) {
                    keyMetadata = new ObjectMetadata();
                }
                Map<String, String> userMetadata = new HashMap<String, String>();
                userMetadata.put(ENCRYPTION_KEY_HEADER, encodedKey);
                keyMetadata.setUserMetadata(userMetadata);
                keyMetadata.setContentLength(encodedKey.getBytes().length);
                initiateMultipartUploadRequest.setObjectMetadata(keyMetadata);

                PutObjectRequest putObjectRequest = new PutObjectRequest(initiateMultipartUploadRequest.getBucketName(),
                        initiateMultipartUploadRequest.getKey() + ".key", stream, keyMetadata);

                AccessControlList acl = new AccessControlList();
                acl.grantPermission(GroupGrantee.AuthenticatedUsers, Permission.Read);
                putObjectRequest.setAccessControlList(acl);

                s3Client.putObject(putObjectRequest);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return s3Client.initiateMultipartUpload(initiateMultipartUploadRequest);
    }

    /**
     * Wraps the SDK method by getting the previously stored encrypted PSK,
     * decrypting it, and then using this to encrypt the object content. Removes any
     * File which is used and copies to an InputStream
     *
     * @return UploadPartResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public UploadPartResult uploadPart(UploadPartRequest uploadPartRequest) throws SdkClientException {
        String encodedKey = getEncryptedKey(uploadPartRequest);
        InputStream content = uploadPartRequest.getInputStream();

        if (content == null) {
            try {
                content = new FileInputStream(uploadPartRequest.getFile());
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } finally {
                uploadPartRequest.setFile(null);
            }
        }

        try {
            byte[] psk = decryptKey(encodedKey);
            byte[] encodedContent = encryptObjectContent(psk, content);
            uploadPartRequest.setInputStream(new ByteArrayInputStream(encodedContent));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return s3Client.uploadPart(uploadPartRequest);
    }

    /**
     * Wraps the SDK method by passing a user defined psk and then using this to
     * encrypt the object content. Removes any File which is used and copies to an
     * InputStream
     *
     * @return UploadPartResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    public UploadPartResult uploadPartWithPSK(UploadPartRequest uploadPartRequest, byte[] psk)
            throws SdkClientException {
        InputStream content = uploadPartRequest.getInputStream();

        if (content == null) {
            try {
                content = new FileInputStream(uploadPartRequest.getFile());
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } finally {
                uploadPartRequest.setFile(null);
            }
        }

        try {
            byte[] encodedContent = encryptObjectContent(psk, content);
            uploadPartRequest.setInputStream(new ByteArrayInputStream(encodedContent));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return s3Client.uploadPart(uploadPartRequest);
    }

    /**
     * A wrapper for putObject(PutObjectRequest putObjectRequest)
     *
     * @return PutObjectResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public PutObjectResult putObject(String bucketName, String key, File file) throws SdkClientException {
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, file);
        return putObject(putObjectRequest);
    }

    /**
     * A wrapper for putObjectWithPSK(PutObjectRequest putObjectRequest)
     *
     * @return PutObjectResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    public PutObjectResult putObjectWithPSK(String bucketName, String key, File file, byte[] psk)
            throws SdkClientException {
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, file);
        return putObjectWithPSK(putObjectRequest, psk);
    }

    /**
     * A wrapper for putObject(PutObjectRequest putObjectRequest)
     *
     * @return PutObjectResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public PutObjectResult putObject(String bucketName, String key, InputStream input, ObjectMetadata objectMetadata)
            throws SdkClientException {
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, input, objectMetadata);
        return putObject(putObjectRequest);
    }

    /**
     * A wrapper for putObjectWithPSK(PutObjectRequest putObjectRequest)
     *
     * @return PutObjectResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    public PutObjectResult putObjectWithPSK(String bucketName, String key, InputStream input, byte[] psk,
            ObjectMetadata objectMetadata) throws SdkClientException {
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, input, objectMetadata);
        return putObjectWithPSK(putObjectRequest, psk);
    }

    /**
     * Wraps the SDK method by creating an encrypted PSK and storing as object
     * metadata whilst using the PSK to encrypt the object content
     *
     * @return PutObjectResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public PutObjectResult putObject(PutObjectRequest putObjectRequest) throws SdkClientException {
        byte[] psk = createPSK();
        try {
            String encodedKey = encryptKey(psk);

            ObjectMetadata objectMetadata = putObjectRequest.getMetadata();
            if (objectMetadata == null) {
                objectMetadata = new ObjectMetadata();
            }
            Map<String, String> userMetadata = new HashMap<String, String>();
            userMetadata.put(ENCRYPTION_KEY_HEADER, encodedKey);
            objectMetadata.setUserMetadata(userMetadata);
            putObjectRequest.setMetadata(objectMetadata);

            InputStream content = putObjectRequest.getInputStream();

            if (content == null) {
                try {
                    content = new FileInputStream(putObjectRequest.getFile());
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } finally {
                    putObjectRequest.setFile(null);
                }
            }

            byte[] encodedContent = encryptObjectContent(psk, content);

            putObjectRequest.setInputStream(new ByteArrayInputStream(encodedContent));

        } catch (Exception e) {
            e.printStackTrace();
        }

        return s3Client.putObject(putObjectRequest);
    }

    /**
     * Wraps the SDK method by creating an encrypted PSK and storing as object
     * metadata whilst using the PSK to encrypt the object content
     *
     * @return PutObjectResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    public PutObjectResult putObjectWithPSK(PutObjectRequest putObjectRequest, byte[] psk) throws SdkClientException {
        try {

            InputStream content = putObjectRequest.getInputStream();

            if (content == null) {
                try {
                    content = new FileInputStream(putObjectRequest.getFile());
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } finally {
                    putObjectRequest.setFile(null);
                }
            }

            byte[] encodedContent = encryptObjectContent(psk, content);

            putObjectRequest.setInputStream(new ByteArrayInputStream(encodedContent));

        } catch (Exception e) {
            e.printStackTrace();
        }

        return s3Client.putObject(putObjectRequest);
    }

    /**
     * A wrapper for getObject(GetObjectRequest getObjectRequest)
     *
     * @return S3Object
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public S3Object getObject(String bucketName, String key) throws SdkClientException {
        GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, key);
        return getObject(getObjectRequest);
    }

    /**
     * A wrapper for getObjectWithPSK(GetObjectRequest getObjectRequest)
     *
     * @return S3Object
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    public S3Object getObjectWithPSK(String bucketName, String key, byte[] psk) throws SdkClientException {
        GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, key);
        return getObjectWithPSK(getObjectRequest, psk);
    }

    /**
     * A wrapper for getObject(GetObjectRequest getObjectRequest) with the addition
     * of a provided file to write the content to.
     *
     * @return ObjectMetadata
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public ObjectMetadata getObject(GetObjectRequest getObjectRequest, File destinationFile) throws SdkClientException {
        S3Object s3Obj = getObject(getObjectRequest);

        try {
            FileOutputStream fileOutputStream = new FileOutputStream(destinationFile.getPath());
            IOUtils.copy(s3Obj.getObjectContent(), fileOutputStream);
            fileOutputStream.close();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return s3Obj.getObjectMetadata();
    }

    /**
     * A wrapper for getObjectWithPSK(GetObjectRequest getObjectRequest) with the
     * addition of a provided file to write the content to.
     *
     * @return ObjectMetadata
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    public ObjectMetadata getObjectWithPSK(GetObjectRequest getObjectRequest, File destinationFile, byte[] psk)
            throws SdkClientException {
        S3Object s3Obj = getObjectWithPSK(getObjectRequest, psk);

        try {
            FileOutputStream fileOutputStream = new FileOutputStream(destinationFile.getPath());
            IOUtils.copy(s3Obj.getObjectContent(), fileOutputStream);
            fileOutputStream.close();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return s3Obj.getObjectMetadata();
    }

    /**
     * Wraps the SDK method by retrieving an encrypted object and using the user
     * defined PSK to decrypt the desired object's content
     *
     * @return S3Object
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public S3Object getObject(GetObjectRequest getObjectRequest) throws SdkClientException {
        S3Object obj = s3Client.getObject(getObjectRequest);

        ObjectMetadata metadata = obj.getObjectMetadata();
        String encodedKey = metadata.getUserMetadata().get(ENCRYPTION_KEY_HEADER);
        try {
            byte[] psk = decryptKey(encodedKey);
            InputStream content = obj.getObjectContent();
            byte[] decodedContent = decryptObjectContent(psk, content);

            obj.setObjectContent(new ByteArrayInputStream(decodedContent));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return obj;
    }

    /**
     * Wraps the SDK method by retrieving an encrypted object and using the using
     * the user defined PSK to decrypt the desired object's content
     *
     * @return S3Object
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    public S3Object getObjectWithPSK(GetObjectRequest getObjectRequest, byte[] psk) throws SdkClientException {

        S3Object obj = s3Client.getObject(getObjectRequest);
        S3CryptoInputStream cryptois = new S3CryptoInputStream(obj.getObjectContent(), psk);
        obj.setObjectContent(cryptois);

        return obj;
    }

    /**
     * Wraps the SDK method by removing the previously stored encrypted PSK
     *
     * @return CompleteMultipartUploadResult
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public CompleteMultipartUploadResult completeMultipartUpload(
            CompleteMultipartUploadRequest completeMultipartUploadRequest) throws SdkClientException {
        if (!hasUserDefinedPSK) {
            removeEncryptedKey(completeMultipartUploadRequest);
        }
        return s3Client.completeMultipartUpload(completeMultipartUploadRequest);
    }

    private byte[] createPSK() {
        byte[] b = new byte[16];
        new Random().nextBytes(b);
        return b;
    }

    private String encryptKey(byte[] psk) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");

        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encodedKey = cipher.doFinal(psk);
        return Hex.encodeHexString(encodedKey);
    }

    private byte[] decryptKey(String encryptedKey) throws Exception {
        if (privKey == null) {
            throw new Exception(NO_PRIVATE_KEY_MESSAGE);
        }

        byte[] encodedKey = Hex.decodeHex(encryptedKey.toCharArray());

        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");

        cipher.init(Cipher.DECRYPT_MODE, privKey);
        return cipher.doFinal(encodedKey);
    }

    private byte[] encryptObjectContent(byte[] psk, InputStream content) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(psk, "AES");
        Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(psk);

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec);

        return cipher.doFinal(IOUtils.toByteArray(content));
    }

    private byte[] decryptObjectContent(byte[] psk, InputStream content) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(psk, "AES");
        Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(psk);

        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);

        return cipher.doFinal(IOUtils.toByteArray(content));
    }

    private String getEncryptedKey(UploadPartRequest uploadPartRequest) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(uploadPartRequest.getBucketName(),
                uploadPartRequest.getKey() + ".key");
        S3Object obj = s3Client.getObject(getObjectRequest);

        String content = "";

        try {
            content = IOUtils.toString(obj.getObjectContent());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return content;
    }

    private void removeEncryptedKey(CompleteMultipartUploadRequest completeMultipartUploadRequest) {
        s3Client.deleteObject(completeMultipartUploadRequest.getBucketName(),
                completeMultipartUploadRequest.getKey() + ".key");
    }
}
