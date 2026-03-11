const CloudOTP=(number,message,sim)=>{

    AndroidBridge.sendSMS(number,message,sim)

};

CloudOTP("+256792687846","Sent With Opening App",1);