package dp.s3crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;

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

public class S3CryptoClient extends AmazonS3Client implements S3Crypto {

	private RSAPrivateKey privKey;
	private final String ENCRYPTION_KEY_HEADER = "Pskencrypted";
	private AmazonS3Client s3Client;

	public S3CryptoClient(ClientConfiguration clientConfiguration, RSAPrivateKey privKey) {
		s3Client = new AmazonS3Client();
		s3Client.builder().setClientConfiguration(clientConfiguration);
		s3Client.builder().build();
		this.privKey = privKey;
	}

	/**
	 * Wraps the SDK method by creating an encrypted PSK which is stored as object
	 * metadata and temporarily as its own file while the multipart upload is in
	 * progress.
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return InitiateMultipartUploadResult
	 */
	@Override
	public InitiateMultipartUploadResult initiateMultipartUpload(
			InitiateMultipartUploadRequest initiateMultipartUploadRequest)
			throws SdkClientException, AmazonServiceException {

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

		return s3Client.initiateMultipartUpload(initiateMultipartUploadRequest);
	}

	/**
	 * Wraps the SDK method by getting the previously stored encrypted PSK,
	 * decrypting it, and then using this to encrypt the object content. Removes any
	 * File which is used and copies to an InputStream
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return UploadPartResult
	 */
	@Override
	public UploadPartResult uploadPart(UploadPartRequest uploadPartRequest)
			throws SdkClientException, AmazonServiceException {
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
	 * A wrapper for putObject(PutObjectRequest putObjectRequest)
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return PutObjectResult
	 */
	@Override
	public PutObjectResult putObject(String bucketName, String key, File file)
			throws SdkClientException, AmazonServiceException {
		PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, file);
		return putObject(putObjectRequest);
	}

	/**
	 * A wrapper for putObject(PutObjectRequest putObjectRequest)
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return PutObjectResult
	 */
	@Override
	public PutObjectResult putObject(String bucketName, String key, InputStream input, ObjectMetadata objectMetadata)
			throws SdkClientException, AmazonServiceException {
		PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, input, objectMetadata);
		return putObject(putObjectRequest);
	}

	/**
	 * Wraps the SDK method by creating an encrypted PSK and storing as object metadata
	 * whilst using the PSK to encrypt the object content
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return PutObjectResult
	 */
	@Override
	public PutObjectResult putObject(PutObjectRequest putObjectRequest)
			throws SdkClientException, AmazonServiceException {
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
	 * A wrapper for getObject(GetObjectRequest getObjectRequest)
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return S3Object
	 */
	@Override
	public S3Object getObject(String bucketName, String key) throws SdkClientException, AmazonServiceException {
		GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, key);
		return getObject(getObjectRequest);
	}

	/**
	 * A wrapper for getObject(GetObjectRequest getObjectRequest) with the addition of
	 * a provided file to write the content to. 
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return ObjectMetadata 
	 */
	@Override
	public ObjectMetadata getObject(GetObjectRequest getObjectRequest, File destinationFile)
			throws SdkClientException, AmazonServiceException {
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
	 * Wraps the SDK method by retrieving an encrypted object and using the encrypted PSK
	 * stored as metadata which is firstly decrypted, to decrypt the desired object's 
	 * content
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return S3Object
	 */
	@Override
	public S3Object getObject(GetObjectRequest getObjectRequest) throws SdkClientException, AmazonServiceException {
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
	 * Wraps the SDK method by removing the previously stored encrypted PSK
	 * 
	 * @throws SdkClientException
	 * @throws AmazonServiceException
	 * 
	 * @return CompleteMultipartUploadResult
	 */
	@Override
	public CompleteMultipartUploadResult completeMultipartUpload(
			CompleteMultipartUploadRequest completeMultipartUploadRequest)
			throws SdkClientException, AmazonServiceException {
		removeEncryptedKey(completeMultipartUploadRequest);
		return s3Client.completeMultipartUpload(completeMultipartUploadRequest);
	}

	private byte[] createPSK() {
		byte[] b = new byte[16];
		new Random().nextBytes(b);
		return b;
	}

	private String encryptKey(byte[] psk) throws Exception {
		Cipher cipher = Cipher.getInstance("RSA");

		cipher.init(Cipher.ENCRYPT_MODE, privKey);
		byte[] encodedKey = cipher.doFinal(psk);
		return Hex.encodeHexString(encodedKey);
	}

	private byte[] decryptKey(String encryptedKey) throws Exception {
		byte[] encodedKey = Hex.decodeHex(encryptedKey.toCharArray());

		Cipher cipher = Cipher.getInstance("RSA");

		RSAPrivateCrtKey privk = (RSAPrivateCrtKey) privKey;
		RSAPublicKeySpec publicKeySpec = new java.security.spec.RSAPublicKeySpec(privk.getModulus(),
				privk.getPublicExponent());
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");
		PublicKey pubKey = keyFactory.generatePublic(publicKeySpec);

		cipher.init(Cipher.DECRYPT_MODE, pubKey);
		return cipher.doFinal(encodedKey);
	}

	private byte[] encryptObjectContent(byte[] psk, InputStream content) throws Exception {
		SecretKeySpec secretKey = new SecretKeySpec(psk, "AES");
		Cipher cipher = Cipher.getInstance("AES");
		cipher.init(Cipher.ENCRYPT_MODE, secretKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CipherOutputStream cipherStream = new CipherOutputStream(out, cipher);

		byte[] b = IOUtils.toByteArray(content);

		cipherStream.write(b);
		cipherStream.close();

		return b;
	}

	private byte[] decryptObjectContent(byte[] psk, InputStream content) throws Exception {
		SecretKeySpec secretKey = new SecretKeySpec(psk, "AES");
		Cipher cipher = Cipher.getInstance("AES");
		cipher.init(Cipher.DECRYPT_MODE, secretKey);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CipherOutputStream cipherStream = new CipherOutputStream(out, cipher);

		byte[] b = IOUtils.toByteArray(content);

		cipherStream.write(b);
		cipherStream.close();

		return b;
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
