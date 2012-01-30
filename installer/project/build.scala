import sbt._
import Keys._
import com.typesafe.packager.Keys._
import sbt.Keys._
import com.typesafe.packager.PackagerPlugin._
import collection.mutable.ArrayBuffer
import com.typesafe.packager.windows.WixHelper._

object ScalaDistro extends Build {

  // TODO - Pull this zip from the latest build version of scala we wish to release.  Maybe publish into a repo somewhere....

  val scalaDistZipFile = TaskKey[File]("scala-dist-zip-file")
  val scalaDistZipLocation = SettingKey[File]("scala-dist-zip-location")  
  val scalaDistDir = TaskKey[File]("scala-dist-dir", "Resolves the Scala distribution and opens it into the desired location.")


  val root = (Project("scala-installer", file(".")) 
              settings(packagerSettings:_*) 
              settings(
    version := "2.9.1",

    // Pulling latest distro code. TODO - something useful....
    scalaDistZipLocation <<= target apply (_ / "dist"),
    scalaDistZipFile <<= sourceDirectory map { src =>
      val distdir = src / "dist"
      IO listFiles distdir filter (_.getName != "README") find (!_.isDirectory) getOrElse error("Please place a zipped scala distribution to package in: " + distdir.getAbsolutePath)
    },
    scalaDistDir <<= (version, scalaDistZipFile, scalaDistZipLocation) map { (v, file, dir) =>
       if(!dir.exists) dir.mkdirs()
       import dispatch._
       val marker = dir / "dist.exploded"
       if(!marker.exists) {
         // Unzip distro to local filesystem.
         IO.unzip(file, dir)   
         IO.touch(marker)
      }
      IO listFiles dir  find (_.isDirectory) getOrElse error("could not find scala distro from " + file.getAbsolutePath + ". You may need to clean the project.")
    },
    // Windows installer configuration
    name in Windows := "scala",
    lightOptions ++= Seq("-ext", "WixUIExtension", "-cultures:en-us"),
    //mappings in packageMsi in Windows <++= scalaDistDir map { (dir) =>  (dir.*** --- dir) x relativeTo(dir) },
    wixConfig <<= (version, scalaDistDir, sourceDirectory in Windows) map generateWindowsXml,

    // Linux Configuration
    name in Linux := "scala",
    maintainer := "Josh Suereth <joshua.suereth@typesafe.com>",
    packageSummary := "Programming Language for the JVM",
    packageDescription := """This includes all the utilities used by the Scala programming language,
  a blended object-functional language for the JVM.""",
    // TODO - Put jline in sub-folder of /usr/share/java
    linuxPackageMappings <+= scalaDistDir map { dir =>
      val jardir = dir / "lib"
      val jars = for {
        (file, name) <- (jardir ** "*.jar") x { f => IO.relativize(jardir, f) }
      } yield file -> ("/usr/share/java/" + name)
      (packageMapping(jars:_*) withPerms "0644")
    },
    linuxPackageMappings <+= scalaDistDir map { dir =>
      val jardir = dir / "misc" / "scala-devel" / "plugins"
      val jars = for {
        (file, name) <- (jardir ** "*.jar") x { f => IO.relativize(jardir, f) }
      } yield file -> ("/usr/share/scala/plugins/" + name)
      (packageMapping(jars:_*) withPerms "0644")
    },
    // TODO - Figure out how to setup maven repo metadata for these.
    
    // TODO - Fix binaries before copying
    linuxPackageMappings <+= (scalaDistDir, sourceDirectory, streams) map { (dir, sdir, s) =>
      val patchdir = sdir / "linux" / "patch"         
      val scriptdir = dir / "bin"
      val patcheddir = dir / "patched-bin"
      
      val scripts = for {
        (file, name) <- (scriptdir ** ("*" -- "*.bat") --- scriptdir) x { f => IO.relativize(scriptdir, f) }
        patchfile = patchdir / (name + ".patch")
        patchedfile = if(patchfile.exists) patcheddir / name else file
      } yield {        
        if(!patchedfile.exists || (patchedfile.lastModified < patchfile.lastModified)) {
          IO.copyFile(file, patchedfile)
          Process(Seq("patch", "-s", "-f", patchedfile.getAbsolutePath, patchfile.getAbsolutePath)) ! s.log match {
            case 0 => ()
            case _ => sys.error("Could not apply script patch file!.")
          }
        }
        patchedfile -> ("/usr/bin/" + name)
      }
      (packageMapping(scripts:_*) withPerms "0755")
    },
    linuxPackageMappings <+= scalaDistDir map { dir =>
      val mandir = dir / "man" / "man1"
      val manpages = for {
        (file, name) <- (mandir ** "*.1") x { f => IO.relativize(mandir, f) }
      } yield file -> ("/usr/share/man/man1/" + name + ".gz")
      (packageMapping(manpages:_*) withPerms "0644" gzipped) asDocs()
    },  
    linuxPackageMappings <+= (sourceDirectory in Linux) map { bd =>
      packageMapping(
        (bd / "copyright") -> "/usr/share/doc/scala/copyright"
      ) withPerms "0644" asDocs()
    }, 
    
    // RPM SPECIFIC
    name in Rpm := "scala",
    rpmRelease := "1",
    rpmVendor := "EPFL/Typesafe, Inc.",
    rpmUrl := Some("http://github.com/scala/scala"),
    rpmLicense := Some("BSD"),
    
    // Debian Specific
    name in Debian := "scala",
    debianPackageDependencies += "openjdk-6-jre | java6-runtime",
    debianPackageDependencies += "libjansi-java",
    linuxPackageMappings in Debian <+= (sourceDirectory) map { bd =>
      (packageMapping(
        (bd / "debian/changelog") -> "/usr/share/doc/scala/changelog.gz"
      ) withUser "root" withGroup "root" withPerms "0644" gzipped) asDocs()
    }
  ))

  def generateWindowsXml(version: String, dir: File, winDir: File): scala.xml.Node = {
    val (libIds, libDirXml) = generateComponentsAndDirectoryXml(dir / "lib")
    val (miscIds, miscDirXml) = generateComponentsAndDirectoryXml(dir / "misc")
    val docdir = dir / "doc"
    val develdocdir = docdir / "scala-devel-docs"
    val (binIds: Seq[String], binDirXml: scala.xml.Node) = {
      val (idseqs, xmls) = IO.listFiles(dir / "bin").toSeq map (generateComponentsAndDirectoryXml(_, "bin_")) unzip
      val ids: Seq[String] = idseqs.flatten
      ids -> (<xml:group> { xmls } </xml:group>)
    }
    val (apiIds, apiDirXml) = generateComponentsAndDirectoryXml(develdocdir / "api", "api_")
    val (exampleIds, exampleDirXml) = generateComponentsAndDirectoryXml(develdocdir / "examples", "ex_")
    val (tooldocIds, tooldocDirXml) = generateComponentsAndDirectoryXml(develdocdir / "tools", "tools_")
    val (srcIds, srcDirXml) = generateComponentsAndDirectoryXml(dir / "src", "src_")
    
    (<Wix xmlns='http://schemas.microsoft.com/wix/2006/wi'>
     <Product Id='7606e6da-e168-42b5-8345-b08bf774cb30' 
            Name='The Scala Programming Language' 
            Language='1033'
            Version={version}
            Manufacturer='LAMP/EPFL and Typesafe, Inc.' 
            UpgradeCode='6061c134-67c7-4fb2-aff5-32b01a186967'>
      <Package Description='Scala Programming Language.'
                Comments='Scala Progamming language for use in Windows.'
                Manufacturer='LAMP/EPFL and Typesafe, Inc.' 
                InstallerVersion='200' 
                InstallScope='perMachine'
                Compressed='yes'/>
 
      <Media Id='1' Cabinet='scala.cab' EmbedCab='yes' />
 
      <Directory Id='TARGETDIR' Name='SourceDir'>
        <Directory Id='ProgramMenuFolder'>
          <Directory Id='ApplicationProgramsFolder' Name='scala'/>
        </Directory>
        <Directory Id='ProgramFilesFolder' Name='PFiles'>
          <Directory Id='INSTALLDIR' Name='scala'>
            <Directory Id='bindir' Name='bin'>
              {binDirXml}
              <Component Id='ScalaBinPath' Guid='244b8829-bd74-40ff-8c1d-5717be94538d'>
                  <CreateFolder/>
                  <Environment Id="PATH" Name="PATH" Value="[INSTALLDIR]\bin" Permanent="no" Part="last" Action="set" System="yes" />
               </Component>
            </Directory>
            {libDirXml}
            {miscDirXml}
            {srcDirXml}
            <Directory Id='DOCDIRECTORY' Name='doc'>
              <!-- TODO - README -->
              <Directory Id='devel_docs_dir' Name='devel-docs'>
                {apiDirXml}
                {exampleDirXml}
                {tooldocDirXml}
              </Directory>
            </Directory>
          </Directory>
         </Directory>
      </Directory>
      <DirectoryRef Id='ApplicationProgramsFolder'>
        <Component Id='ApiShortcut' Guid='1607077c-58ca-4b4a-ac82-277a83b9360a'>
          <Shortcut Id="ApplicationStartMenuShortcut"
                    Name='Scala API Documentation'
                    Description='Scala library API documentation (web)'
                    Target="[DOCDIRECTORY]/devel-docs/api/index.html"/>
          <RemoveFolder Id="ApplicationProgramsFolder" On="uninstall"/>
          <RegistryValue Root='HKCU' Key='Software\Microsoft\scala' Name='installed' Type='integer' Value='1' KeyPath='yes'/>
        </Component>
      </DirectoryRef>
      
      <Feature Id='Complete' Title='The Scala Programming Language' Description='The windows installation of the Scala Programming Language'
         Display='expand' Level='1' ConfigurableDirectory='INSTALLDIR'>
        <Feature Id='lang' Title='The core scala language.' Level='1' Absent='disallow'>
          { for(ref <- (libIds ++ miscIds ++ binIds)) yield <ComponentRef Id={ref}/> }
        </Feature>
         <Feature Id='ScalaPathF' Title='Update system PATH' Description='This will add scala binaries (scala, scalac, scaladoc, scalap) to your windows system path.' Level='1'>
          <ComponentRef Id='ScalaBinPath'/>
        </Feature>
        <Feature Id='fdocs' Title='Documentation for the Scala library' Description='This will install the Scala documentation.' Level='1'>
          <Feature Id='fapi' Title='API Documentation' Description='Scaladoc API html.' Level='1'>
            { for(ref <- apiIds) yield <ComponentRef Id={ref}/> }
             <Feature Id='fapilink' Title='Start Menu link' Description='Menu shortcut to Scala API documentation.' Level='1'>
              <ComponentRef Id='ApiShortcut'/>
            </Feature>
          </Feature>
          <Feature Id='ftooldoc' Title='Tool documentation' Description='Manuals for scala, scalac, scaladoc, etc.' Level='1'>
            { for(ref <- tooldocIds) yield <ComponentRef Id={ref}/> }
          </Feature>
          <Feature Id='fexample' Title='Example Code' Description='Scala code examples.' Level='100'>
            { for(ref <- exampleIds) yield <ComponentRef Id={ref}/> }
          </Feature>
        </Feature>
        <Feature Id='fsrc' Title='Sources' Description='This will install the Scala source files for the binaries.' Level='100'>
          { for(ref <- srcIds) yield <ComponentRef Id={ref}/> }
        </Feature>
      </Feature>
      <!--<Property Id="JAVAVERSION64">
        <RegistrySearch Id="JavaVersion64"
                        Root="HKLM"
                        Key="SOFTWARE\Javasoft\Java Runtime Environment"
                        Name="CurrentVersion"
                        Type="raw"
                        Win64="yes"/>
      </Property>-->
      <Property Id="JAVAVERSION">
        <RegistrySearch Id="JavaVersion"
                        Root="HKLM"
                        Key="SOFTWARE\Javasoft\Java Runtime Environment"
                        Name="CurrentVersion"
                        Type="raw"
                        Win64="no"/>
      </Property>
      <!--<Condition Message="This application requires a JVM available.  Please install Java, then run this installer again.">
        <![CDATA[Installed OR JAVAVERSION]]>
      </Condition>-->
      <MajorUpgrade 
         AllowDowngrades="no" 
         Schedule="afterInstallInitialize"
         DowngradeErrorMessage="A later version of [ProductName] is already installed.  Setup will now exit."/>  
      <UIRef Id="WixUI_FeatureTree"/>
      <UIRef Id="WixUI_ErrorProgressText"/>
      <Property Id="WIXUI_INSTALLDIR" Value="INSTALLDIR"/>
      <WixVariable Id="WixUILicenseRtf" Value={(winDir / "License.rtf").getAbsolutePath } />      
   </Product>
    </Wix>)
  }
}
