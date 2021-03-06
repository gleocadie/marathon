package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.raml.{ Endpoint, Network, NetworkMode, PersistentVolumeInfo, Pod, PodContainer, PodPersistentVolume, PodSchedulingPolicy, PodUpgradeStrategy, Resources, UnreachableDisabled, VolumeMount }
import Normalization._
import org.scalatest.Inside

class PodNormalizationTest extends UnitTest with Inside {

  "PodNormalization" when {
    "normalizing container endpoints" should {
      "convert empty hostPort to zero, for bridge mode only" in new Fixture() {
        val endpoints = Seq(Endpoint(name = "e1", containerPort = Some(1)))
        val p = Pod(
          id = "foo",
          containers = Seq(PodContainer(name = "c1", resources = Resources(), endpoints = endpoints)),
          networks = Seq(Network(mode = NetworkMode.ContainerBridge))
        )
        val withBridgeNetwork = p.normalize
        inside(withBridgeNetwork.containers) {
          case ct :: Nil =>
            inside(ct.endpoints) {
              case ep :: Nil =>
                ep.hostPort.value shouldBe 0
            }
        }

        val withContainerNetwork = p.copy(
          networks = Seq(Network(mode = NetworkMode.Container, name = Some("n1")))).normalize
        inside(withContainerNetwork.containers) {
          case ct :: Nil =>
            inside(ct.endpoints) {
              case ep :: Nil =>
                ep.hostPort shouldBe 'empty
            }
        }

        val withHostNetwork = p.copy(networks = Seq(Network(mode = NetworkMode.Host))).normalize
        inside(withHostNetwork.containers) {
          case ct :: Nil =>
            inside(ct.endpoints) {
              case ep :: Nil =>
                ep.hostPort.value shouldBe 0
            }
        }
      }
    }
    "normalizing network name" should {
      val template = Pod(id = "foo", containers = Seq(PodContainer(name = "c", resources = Resources()))
      )
      "without default network name" in new Fixture() {
        // no name and no default name == error?!
        val withoutNetworkName = template.copy(networks = Seq(Network()))
        val ex = intercept[NormalizationException] {
          withoutNetworkName.normalize
        }
        ex.msg shouldBe NetworkNormalizationMessages.ContainerNetworkNameUnresolved

        // leave a non-empty network name unchanged
        val withNetworkName = template.copy(networks = Seq(Network(name = Some("net1"))))
        inside(withNetworkName.normalize.networks) {
          case net :: Nil =>
            net.name.value shouldBe "net1"
        }
      }
      "with default network name" in new Fixture(PodNormalization.Configuration(Some("default1"))) {
        // replace empty network name with the default
        val withoutNetworkName = template.copy(networks = Seq(Network()))
        inside(withoutNetworkName.normalize.networks) {
          case net :: Nil =>
            net.name.value shouldBe "default1"
        }

        // leave a non-empty network name unchanged
        val withNetworkName = template.copy(networks = Seq(Network(name = Some("net1"))))
        inside(withNetworkName.normalize.networks) {
          case net :: Nil =>
            net.name.value shouldBe "net1"
        }
      }
    }

    "normalizing a pod with a persistent volume" should {
      val template = Pod(
        id = "foo",
        containers = Seq(PodContainer(
          name = "c", resources = Resources(), volumeMounts = Seq(VolumeMount("foo", "foo")))),
        volumes = Seq(PodPersistentVolume("foo", PersistentVolumeInfo(size = 1))))

      "return default scheduling for resident pods, if it is not provided" in new Fixture() {
        inside(template.normalize.scheduling) {
          case Some(scheduling) =>
            scheduling.upgrade shouldBe Some(PodUpgradeStrategy(minimumHealthCapacity = 0.5, maximumOverCapacity = 0.0))
            scheduling.unreachableStrategy shouldBe Some(UnreachableDisabled())
        }
      }

      "for pods without persistent volume the normalization should return the original pod" in new Fixture() {
        val pod = template.copy(scheduling = Some(PodSchedulingPolicy(
          upgrade = Some(PodUpgradeStrategy(minimumHealthCapacity = 0.7, maximumOverCapacity = 0.0)),
          unreachableStrategy = Some(UnreachableDisabled()))))
        inside(pod.normalize.scheduling) {
          case Some(scheduling) =>
            scheduling.upgrade shouldBe Some(PodUpgradeStrategy(minimumHealthCapacity = 0.7, maximumOverCapacity = 0.0))
            scheduling.unreachableStrategy shouldBe Some(UnreachableDisabled())
        }
      }
    }
  }

  abstract class Fixture(config: PodNormalization.Config = PodNormalization.Configuration(None)) {
    protected implicit val normalization: Normalization[Pod] = PodNormalization(config)
  }
}
