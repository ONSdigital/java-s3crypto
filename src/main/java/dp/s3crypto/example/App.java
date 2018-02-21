package dp.s3crypto.example;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;

import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.Permission;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.UploadPartRequest;

import dp.s3crypto.S3CryptoClient;

public class App {
	
	public static String BUCKET = "dp-frontend-florence-file-uploads";
	public static String FILENAME = "cpicoicoptest.csv";
	public static int CHUNK_SIZE = 5 * 1024 * 1024;
	
	public static void main(String[] args) throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(2048);
		KeyPair kp = kpg.generateKeyPair();
		
		PublicKey pub = kp.getPublic();
		RSAPrivateKey pvt = (RSAPrivateKey) kp.getPrivate();
		
		String outFile = "test-key";
		FileOutputStream out = new FileOutputStream("testdata/" +outFile + ".key");
		out.write(pvt.getEncoded());
		out.close();

		out = new FileOutputStream("testdata/"+outFile + ".pub");
		out.write(pub.getEncoded());
		out.close();
		
		S3CryptoClient client = new S3CryptoClient(null, pvt);
		
		Path path = Paths.get("testdata/" + FILENAME);
		byte[] data = Files.readAllBytes(path);
		
		byte[][] chunks = chunkArray(data, CHUNK_SIZE);
		
		InitiateMultipartUploadRequest initiateMultipartUploadRequest = new InitiateMultipartUploadRequest(BUCKET, FILENAME);
		AccessControlList acl = new AccessControlList();
		acl.grantPermission(GroupGrantee.AllUsers, Permission.Read);
		initiateMultipartUploadRequest.setAccessControlList(acl);
		List<PartETag> partETags = new ArrayList<PartETag>();
		ObjectMetadata keyMetadata = new ObjectMetadata();
		keyMetadata.setContentLength(data.length);
		InitiateMultipartUploadResult res = client.initiateMultipartUpload(initiateMultipartUploadRequest);
		String uploadId = res.getUploadId();
		
		for (int i = 0; i < chunks.length; i++) {
			long size = chunks[i].length;
			UploadPartRequest uploadPartRequest = new UploadPartRequest()
					.withBucketName(BUCKET).withKey(FILENAME)
					.withUploadId(uploadId).withPartNumber(i + 1)
					.withInputStream(new ByteArrayInputStream(chunks[i]))
					.withPartSize(size);
			
			partETags.add(client.uploadPart(uploadPartRequest).getPartETag());
		}
		
		CompleteMultipartUploadRequest completeMultipartUploadRequest = new CompleteMultipartUploadRequest();
		completeMultipartUploadRequest.setBucketName(BUCKET);
		completeMultipartUploadRequest.setKey(FILENAME);
		completeMultipartUploadRequest.setUploadId(uploadId);
		completeMultipartUploadRequest.setPartETags(partETags);
		
		CompleteMultipartUploadResult multiResult = client.completeMultipartUpload(completeMultipartUploadRequest);
		
		System.out.println("multi part upload completed: \n" + multiResult.getLocation());
		
		System.out.println("about to download the file!");
		
		GetObjectRequest gor = new GetObjectRequest(BUCKET, FILENAME);
		
		S3Object r = client.getObject(gor);
		
		FileOutputStream f = new FileOutputStream("testdata/newcoicop.csv");
		IOUtils.copy(r.getObjectContent(), f);
		f.close();
		
		System.out.println("downloaded file");		
	}
	
	public static byte[][] chunkArray(byte[] array, int chunkSize) {
        int numOfChunks = (int)Math.ceil((double)array.length / chunkSize);
        byte[][] output = new byte[numOfChunks][];

        for(int i = 0; i < numOfChunks; ++i) {
            int start = i * chunkSize;
            int length = Math.min(array.length - start, chunkSize);

            byte[] temp = new byte[length];
            System.arraycopy(array, start, temp, 0, length);
            output[i] = temp;
        }

        return output;
    }
}
