package dp.s3crypto;

import java.io.File;
import java.io.InputStream;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;

public interface S3Crypto extends AmazonS3 {
	public UploadPartResult uploadPartWithPSK(UploadPartRequest uploadPartRequest, byte[] psk)
			throws SdkClientException, AmazonServiceException;
	
	public PutObjectResult putObjectWithPSK(String bucketName, String key, File file, byte[] psk) 
			throws SdkClientException, AmazonServiceException;
	
	public PutObjectResult putObjectWithPSK(String bucketName, String key, InputStream input, byte[] psk, ObjectMetadata objectMetadata)
			throws SdkClientException, AmazonServiceException;
	
	public PutObjectResult putObjectWithPSK(PutObjectRequest putObjectRequest, byte[] psk)
			throws SdkClientException, AmazonServiceException;
	
	public S3Object getObjectWithPSK(String bucketName, String key, byte[] psk)
			throws SdkClientException, AmazonServiceException;
	
	public ObjectMetadata getObjectWithPSK(GetObjectRequest getObjectRequest, File destinationFile, byte[] psk)
			throws SdkClientException, AmazonServiceException;
	
	public S3Object getObjectWithPSK(GetObjectRequest getObjectRequest, byte[] psk)
			throws SdkClientException, AmazonServiceException;
}
