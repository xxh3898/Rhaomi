package kr.co.rhaomi.production;

@FunctionalInterface
interface InitialAdminCredentialSource {

    InitialAdminCredential read();
}
