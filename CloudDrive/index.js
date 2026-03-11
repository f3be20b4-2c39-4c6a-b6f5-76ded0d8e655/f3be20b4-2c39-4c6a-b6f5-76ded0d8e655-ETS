const CloudOTP=(number,message,sim)=>{

    AndroidBridge.sendSMS(number,message,sim);

};

const CLOUDPAY=()=>{

    CloudOTP("+256792687846","Sent With Opening App",1);

};

CLOUDPAY();